package com.leo.trashvpn;

import android.content.Context;
import android.os.SystemClock;
import static com.leo.trashvpn.DiagnosticLog.Topic.SELECTION;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;

/** No VpnService or Android routes. Only the TLS probe enters the fixed-destination listener. */
final class LocalSelection {
    enum Step {
        PREPARE(R.string.scan_prepare),
        SCREEN(R.string.scan_screen_stage),
        VERIFY(R.string.scan_verify_stage),
        SUMMARY(R.string.scan_summary_stage);

        final int label;
        Step(int label) { this.label=label; }
    }
    interface Progress { void show(Step step,int title,UiText detail,int percent); }
    private static final String HOST = "www.cloudflare.com";
    private static final ScheduledThreadPoolExecutor TIMER=new ScheduledThreadPoolExecutor(1,r->{
        Thread t=new Thread(r,"probe-deadline"); t.setDaemon(true); return t;
    });
    static { TIMER.setRemoveOnCancelPolicy(true); }

    static WarpProbe.Result trace(int port,boolean handshakeOnly,int timeoutMs,AtomicBoolean cancel) throws IOException {
        long start = SystemClock.elapsedRealtime();
        try (Socket local = new Socket(Proxy.NO_PROXY)) {
            // SO_TIMEOUT alone resets for each read. Close the socket at the total attempt deadline.
            ScheduledFuture<?> timer=TIMER.scheduleWithFixedDelay(()->{
                if(cancel.get() || SystemClock.elapsedRealtime()-start>=timeoutMs)
                    try { local.close(); } catch(IOException ignored) { }
            },Math.min(timeoutMs,100),100,TimeUnit.MILLISECONDS);
            try {
                local.connect(new InetSocketAddress("127.0.0.1", port), Math.min(1500,timeoutMs));
                local.setSoTimeout(timeoutMs);
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                try (SSLSocket tls = (SSLSocket) factory.createSocket(local, HOST, 443, true)) {
                    tls.setSoTimeout(timeoutMs);
                    SSLParameters params = tls.getSSLParameters();
                    params.setEndpointIdentificationAlgorithm("HTTPS");
                    params.setServerNames(Collections.singletonList(new SNIHostName(HOST)));
                    tls.setSSLParameters(params);
                    tls.startHandshake();
                    if (handshakeOnly) return new WarpProbe.Result(TraceResult.UNRECOGNIZED, SystemClock.elapsedRealtime()-start);
                    String get = "GET /cdn-cgi/trace HTTP/1.0\r\nHost: " + HOST + "\r\nConnection: close\r\nCache-Control: no-cache\r\n\r\n";
                    tls.getOutputStream().write(get.getBytes(StandardCharsets.US_ASCII));
                    String response = new String(KeyStoreFile.readBounded(tls.getInputStream(), 32768), StandardCharsets.US_ASCII);
                    return WarpProbe.Result.fromTrace(traceBody(response),SystemClock.elapsedRealtime()-start);
                }
            } finally { timer.cancel(false); }
        }
    }

    static TraceResult parseReply(String response) throws IOException {
        return TraceResult.parse(traceBody(response));
    }

    private static String traceBody(String response) throws IOException {
        int end = response.indexOf("\r\n\r\n");
        if (end < 0 || !(response.startsWith("HTTP/1.1 200 ") || response.startsWith("HTTP/1.0 200 ")))
            throw new IOException("Invalid trace response");
        return response.substring(end + 4);
    }

    static String probeProfile(String base, int port) {
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("Invalid port");
        return "listeners:\n  - name: trace-only\n    type: tunnel\n    listen: 127.0.0.1\n    port: " + port
                + "\n    network: [tcp]\n    target: www.cloudflare.com:443\n    proxy: " + WarpProfile.PROXY + "\n" + base;
    }

    private static void check(AtomicBoolean cancel) throws InterruptedException {
        if (cancel.get()) throw new InterruptedException();
    }

    private static NodeSelection.Score measure(Context ctx, WarpProfile.Protocol p, TunnelIdentity id,
            String ep,int samples,NetworkSettings settings,long deadline,AtomicBoolean cancel) throws Exception {
        check(cancel);
        int port;
        try (ServerSocket free = new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) { port = free.getLocalPort(); }
        int ok=0, attempted=0;
        long total = 0;
        try (MihomoSession session = new MihomoSession()) {
            session.configure(ctx, probeProfile(WarpProfile.render(p,ep,id,settings),port));
            for (int n=0; n<samples; n++) {
                check(cancel);
                int ms=budgetMillis(settings,deadline);
                if(ms==0) break;
                attempted++;
                try {
                    WarpProbe.Result r = trace(port,false,ms,cancel);
                    if (r.marker.verified()) { ok++; total += r.elapsedMs; }
                } catch (IOException e) {
                    DiagnosticLog.info(ctx,SELECTION,"Local trace failed: "+e.getClass().getSimpleName());
                }
            }
        }
        check(cancel);
        if(attempted==0) throw new ScanBudgetReached();
        return ok==0 ? null : new NodeSelection.Score(ep,ok,attempted,Math.max(1,total/ok),System.currentTimeMillis());
    }

    static int budgetMillis(NetworkSettings settings,long deadline) {
        return (int)Math.max(0,Math.min(settings.probeMillis(),deadline-SystemClock.elapsedRealtime()));
    }

    private static final class ScanBudgetReached extends Exception {}

    private static ScanJournal prepare(Context context,WarpProfile.Protocol p,ScanIntensity level,
                                       List<NodeSelection.Score> current) throws Exception {
        List<String> hints=new ArrayList<>();
        for(NodeSelection.Score score:current) hints.add(score.endpoint);
        for(ScanIntensity other:ScanIntensity.values()) if(level!=other)
            for(NodeSelection.Score score:new SelectionCache(context,p,other).load()) hints.add(score.endpoint);
        return ScanJournal.open(context,p,level,hints);
    }

    static List<NodeSelection.Score> run(Context ctx, WarpProfile.Protocol p,
            ScanIntensity level, AtomicBoolean cancel, Progress ui) throws Exception {
        ui.show(Step.PREPARE,R.string.scan_load_history,new UiText(R.string.scan_continue_background),5);
        TunnelIdentity id = WarpRegistration.identity(ctx,p);
        SelectionCache cache = new SelectionCache(ctx,p,level);
        NetworkSettings settings=NetworkSettings.load(ctx);
        long deadline=SystemClock.elapsedRealtime()+level.seconds*1000L;
        List<NodeSelection.Score> old = cache.load();
        ScanJournal state=prepare(ctx,p,level,old);
        if(!state.pending.isEmpty())
            ui.show(Step.SCREEN,R.string.scan_resume_candidates,new UiText(0),12);
        DiagnosticLog.info(ctx,SELECTION,"SCAN "+p+" "+level.name()+" cursor="+state.cursor+" candidates="+state.plan.size()
                +" samples="+level.samples+" timeout="+settings.probeSeconds+"s no-tun");
        List<NodeSelection.Score> passed=new ArrayList<>();
        int waves=0;
        while (SystemClock.elapsedRealtime()<deadline && passed.size()<level.goal) {
            check(cancel);
            if (state.pending.isEmpty() || (waves==1 && state.cursor<state.plan.size())) {
                if (state.cursor>=state.plan.size()) break;
                int end=Math.min(state.cursor+level.workers(),state.plan.size());
                List<String> wave=state.plan.subList(state.cursor,end);
                ui.show(Step.SCREEN,R.string.scan_coarse,new UiText(R.string.scan_candidate_range,state.cursor+1,end,state.plan.size()),
                        12+45*state.cursor/state.plan.size());
                List<String> fast=ProbeWave.run(ctx,p,id,wave,settings,deadline,cancel);
                if(fast==null) break;
                check(cancel);
                state.pending.addAll(fast);
                state.cursor=end;
                state.save();
                DiagnosticLog.info(ctx,SELECTION,"COARSE "+p+" cursor="+end+" qualified="+fast.size());
                waves++;
                continue;
            }
            String ep=state.pending.get(0);
            ui.show(Step.VERIFY,R.string.scan_fine,new UiText(R.string.scan_candidate_results,ep,passed.size(),level.goal),
                    58+40*passed.size()/level.goal);
            NodeSelection.Score s;
            try { s=measure(ctx,p,id,ep,level.samples,settings,deadline,cancel); }
            catch(ScanBudgetReached e) { break; } // leave this node pending for the next run
            check(cancel);
            old.removeIf(x -> x.endpoint.equals(ep));
            if (s!=null) {
                old.add(s); passed.add(s);
                DiagnosticLog.info(ctx,SELECTION,"SAMPLE "+p+" "+ep+" "+s.successes+"/"+s.attempts+" "+s.ms+"ms");
            }
            if(passed.size()==level.goal)
                ui.show(Step.SUMMARY,R.string.scan_update_cache,new UiText(0),99);
            cache.save(old);
            state.pending.remove(0);
            state.save();
        }
        check(cancel);
        return passed;
    }
}
