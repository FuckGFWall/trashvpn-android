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
    private static final long H3_CLEANUP_RESERVE_MS=1_000;
    private static final ScheduledThreadPoolExecutor TIMER=new ScheduledThreadPoolExecutor(1,r->{
        Thread t=new Thread(r,"probe-deadline"); t.setDaemon(true); return t;
    });
    static { TIMER.setRemoveOnCancelPolicy(true); }

    static WarpProbe.Result trace(int port,boolean handshakeOnly,int timeoutMs,AtomicBoolean cancel) throws IOException {
        long start = SystemClock.elapsedRealtime();
        Thread caller=Thread.currentThread();
        if (cancel.get() || caller.isInterrupted()) throw new InterruptedIOException();
        try (Socket local = new Socket(Proxy.NO_PROXY)) {
            // SO_TIMEOUT alone resets for each read. Close the socket at the total attempt deadline.
            AtomicBoolean timedOut=new AtomicBoolean();
            ScheduledFuture<?> timer=TIMER.scheduleWithFixedDelay(()->{
                boolean expired=SystemClock.elapsedRealtime()-start>=timeoutMs;
                if (expired) timedOut.set(true);
                if(cancel.get() || caller.isInterrupted() || expired)
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
            } catch (IOException e) {
                if (!timedOut.get()) throw e;
                SocketTimeoutException timeout=new SocketTimeoutException("Trace probe deadline");
                timeout.initCause(e);
                throw timeout;
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
            String ep,ScanIntensity level,NetworkSettings settings,long deadline,AtomicBoolean cancel) throws Exception {
        check(cancel);
        int port;
        try (ServerSocket free = new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) { port = free.getLocalPort(); }
        long[] samples;
        try (MihomoSession session = new MihomoSession()) {
            if (!session.configureBefore(ctx,probeProfile(WarpProfile.render(p,ep,id,settings),port),deadline))
                throw new SelectionVerification.BudgetReached();
            samples=SelectionVerification.measure(level,settings.probeMillis(),deadline,cancel,
                    SystemClock::elapsedRealtime,Thread::sleep,ms->{
                try {
                    WarpProbe.Result r = trace(port,false,ms,cancel);
                    if (r.marker.verified()) return Math.max(1,r.elapsedMs);
                } catch (IOException e) {
                    DiagnosticLog.info(ctx,SELECTION,"Local trace failed: "+e.getClass().getSimpleName());
                }
                return 0;
            });
        }
        check(cancel);
        return samples==null ? null : NodeSelection.Score.measured(ep,samples,System.currentTimeMillis());
    }

    private static ScanJournal prepare(Context context,WarpProfile.Protocol p,ScanIntensity level,
                                       List<NodeSelection.Score> current,boolean resample) throws Exception {
        List<String> hints=new ArrayList<>();
        for(NodeSelection.Score score:current) hints.add(score.endpoint);
        for(ScanIntensity other:ScanIntensity.values()) if(level!=other)
            for(NodeSelection.Score score:new SelectionCache(context,p,other).load()) hints.add(score.endpoint);
        return ScanJournal.open(context,p,level,hints,resample);
    }

    static List<NodeSelection.Score> run(Context ctx, WarpProfile.Protocol p,
            ScanIntensity level, boolean resample, AtomicBoolean cancel, Progress ui) throws Exception {
        // Do not spend the last second of the H3 budget on a new network request:
        // retiring the core profile and committing the resume cursor also take time.
        long deadline=SystemClock.elapsedRealtime()+level.seconds*1000L
                -(p==WarpProfile.Protocol.MASQUE ? H3_CLEANUP_RESERVE_MS : 0);
        ui.show(Step.PREPARE,R.string.scan_load_history,new UiText(R.string.scan_continue_background),5);
        TunnelIdentity id = WarpRegistration.identity(ctx,p);
        SelectionCache cache = new SelectionCache(ctx,p,level);
        NetworkSettings settings=NetworkSettings.load(ctx);
        List<NodeSelection.Score> old = cache.load();
        check(cancel);
        ScanJournal state=prepare(ctx,p,level,old,resample);
        if(!state.pending.isEmpty())
            ui.show(Step.SCREEN,R.string.scan_resume_candidates,new UiText(0),12);
        DiagnosticLog.info(ctx,SELECTION,"SCAN "+p+" "+level.name()+" cursor="+state.cursor+" candidates="+state.plan.size()
                +" samples="+level.samples+" timeout="+settings.probeSeconds+"s budget="+level.seconds+"s no-tun");
        List<NodeSelection.Score> passed=new ArrayList<>();
        int waves=0;
        while (SystemClock.elapsedRealtime()<deadline && passed.size()<level.goal) {
            check(cancel);
            // Check already-qualified H3 candidates before spending more of the budget on speculative IPs.
            if (state.pending.isEmpty() || (p!=WarpProfile.Protocol.MASQUE && waves==1 && state.cursor<state.plan.size())) {
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
            try { s=measure(ctx,p,id,ep,level,settings,deadline,cancel); }
            catch(SelectionVerification.BudgetReached e) { break; } // leave this node pending for the next run
            check(cancel);
            old.removeIf(x -> x.endpoint.equals(ep));
            if (s!=null) {
                old.add(s); passed.add(s);
                DiagnosticLog.info(ctx,SELECTION,String.format(Locale.ROOT,
                        "SAMPLE %s %s %d/%d (%.0f%%) mean=%dms median=%.1fms p90=%dms jitter=%.1fms",
                        p,ep,s.successes,s.attempts,100*s.successRate(),s.ms,s.medianMs,s.p90Ms,s.jitterMs));
            }
            if(passed.size()==level.goal)
                ui.show(Step.SUMMARY,R.string.scan_update_cache,new UiText(0),99);
            cache.save(old);
            state.pending.remove(0);
            state.save();
        }
        check(cancel);
        boolean goalMet=passed.size()>=level.goal;
        boolean exhausted=state.cursor==state.plan.size() && state.pending.isEmpty();
        if (p==WarpProfile.Protocol.MASQUE && (goalMet || exhausted))
            state.finish();
        Collections.sort(passed);
        return passed;
    }
}
