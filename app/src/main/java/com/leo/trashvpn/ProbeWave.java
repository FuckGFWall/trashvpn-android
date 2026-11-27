package com.leo.trashvpn;

import android.content.Context;
import android.os.SystemClock;
import java.io.EOFException;
import java.io.IOException;
import java.net.*;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;

import static com.leo.trashvpn.DiagnosticLog.Topic.SELECTION;

/** One native profile per wave, independent named WARP outbounds; no shared selector or system TUN. */
final class ProbeWave {
    private static final int H3_HANDSHAKE_SECONDS=4;
    // The loopback check includes TLS to the trace host on top of the H3 handshake.
    private static final int H3_ATTEMPT_MS=(H3_HANDSHAKE_SECONDS+1)*1000;
    private static final int MIN_RETRY_MS=1_000;
    private static final long FAILED=-1;

    interface Handshake { long run(int timeoutMs) throws IOException; }

    static final class ProbeResult {
        final long latencyMs;
        final boolean retried;
        private ProbeResult(long latencyMs,boolean retried) { this.latencyMs=latencyMs; this.retried=retried; }
    }

    static String profile(WarpProfile.Protocol p,TunnelIdentity id,List<String> eps,List<Integer> ports) {
        return profile(p,id,eps,ports,NetworkSettings.defaults());
    }
    static String profile(WarpProfile.Protocol p,TunnelIdentity id,List<String> eps,List<Integer> ports,NetworkSettings settings) {
        StringBuilder listeners=new StringBuilder("listeners:\n"), proxies=new StringBuilder("proxies:\n");
        if (eps.isEmpty() || eps.size()>10 || eps.size()!=ports.size()) throw new IllegalArgumentException();
        for (int i=0;i<eps.size();i++) {
            String name="candidate-"+i;
            proxies.append(WarpProfile.proxyYaml(p,eps.get(i),name,id,settings,
                    Math.min(settings.probeSeconds,H3_HANDSHAKE_SECONDS)));
            listeners.append("  - name: probe-").append(i).append("\n    type: tunnel\n    listen: 127.0.0.1\n    port: ")
                .append(ports.get(i)).append("\n    network: [tcp]\n    target: www.cloudflare.com:443\n    proxy: ").append(name).append('\n');
        }
        return "mode: rule\nipv6: false\nallow-lan: false\nlog-level: silent\ngeo-auto-update: false\nfind-process-mode: off\nrules: ['MATCH,REJECT']\n"+listeners+proxies;
    }

    private static boolean transientTransportFailure(IOException failure) {
        // The loopback listener does not report native QUIC errors. Retry only
        // ambiguous transport closures/timeouts, not a visible TLS trust/protocol rejection.
        for (Throwable e=failure; e!=null; e=e.getCause()) {
            if (e instanceof CertificateException || e instanceof SSLPeerUnverifiedException
                    || e instanceof SSLProtocolException || e instanceof ConnectException) return false;
            if (e instanceof SocketTimeoutException || e instanceof EOFException || e instanceof SocketException) return true;
            // Some Android Conscrypt socket paths report EOF/reset without an IOException cause.
            String message=e.getMessage();
            if (e instanceof SSLException && message!=null && ("Connection closed by peer".equalsIgnoreCase(message)
                    || message.endsWith(", Connection reset by peer"))) return true;
        }
        return false;
    }

    static ProbeResult probe(WarpProfile.Protocol p,int configuredMs,long deadline,AtomicBoolean stop,
                             LongSupplier clock,Handshake handshake) throws InterruptedException {
        boolean h3=p==WarpProfile.Protocol.MASQUE;
        int cap=h3 ? Math.min(H3_ATTEMPT_MS,configuredMs) : configuredMs;
        boolean retried=false;
        for (;;) {
            checkRunning(stop);
            long remaining=deadline-clock.getAsLong();
            if (remaining<=0 || (retried && remaining<MIN_RETRY_MS)) return null;
            int timeout=(int)Math.min(remaining,cap);
            try {
                long ms=handshake.run(timeout);
                checkRunning(stop);
                if (clock.getAsLong()>=deadline) return null;
                if (ms>timeout) throw new SocketTimeoutException();
                return new ProbeResult(ms,retried);
            } catch (IOException e) {
                checkRunning(stop);
                if (clock.getAsLong()>=deadline) return null;
                boolean temporary=transientTransportFailure(e);
                if (h3 && !retried && temporary) {
                    retried=true;
                    continue;
                }
                // A transport error on a shortened attempt is not a completed check.
                return temporary && timeout<cap ? null : new ProbeResult(FAILED,retried);
            }
        }
    }

    private static void checkRunning(AtomicBoolean stop) throws InterruptedException {
        if (stop.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    static List<String> run(Context ctx,WarpProfile.Protocol p,TunnelIdentity id,List<String> eps,
                            NetworkSettings settings,long deadline,AtomicBoolean stop) throws Exception {
        checkRunning(stop);
        List<Integer> ports=new ArrayList<>();
        List<ServerSocket> reserved=new ArrayList<>();
        try {
            for (String ignored:eps) {
                ServerSocket s=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1")); reserved.add(s); ports.add(s.getLocalPort());
            }
        } finally { for (ServerSocket s:reserved) s.close(); }
        try (MihomoSession session=new MihomoSession()) {
            if (!session.configureBefore(ctx,profile(p,id,eps,ports,settings),deadline)) return null;
            checkRunning(stop);
            ExecutorService workers=Executors.newFixedThreadPool(eps.size());
            try {
                List<Callable<ProbeResult>> jobs=new ArrayList<>();
                for (int port:ports) jobs.add(()->probe(p,settings.probeMillis(),deadline,stop,SystemClock::elapsedRealtime,
                        ms->LocalSelection.trace(port,true,ms,stop).elapsedMs));
                long remaining=deadline-SystemClock.elapsedRealtime();
                if (remaining<=0) return null;
                List<Future<ProbeResult>> results=workers.invokeAll(jobs,remaining,TimeUnit.MILLISECONDS);
                Map<String,ProbeResult> passed=new LinkedHashMap<>();
                int retries=0;
                for (int i=0;i<eps.size();i++) {
                    // Never advance the persisted cursor past a wave censored by the deadline.
                    if (results.get(i).isCancelled()) return null;
                    ProbeResult result=results.get(i).get();
                    if (result==null) return null;
                    if (result.retried) retries++;
                    if (result.latencyMs!=FAILED) passed.put(eps.get(i),result);
                }
                if (retries>0) DiagnosticLog.info(ctx,SELECTION,"COARSE RETRY "+p+" "+retries+"/"+eps.size());
                List<String> out=new ArrayList<>(passed.keySet());
                // A quick handshake on an already warm outbound must not jump ahead of first-pass successes.
                out.sort(Comparator.comparing((String ep)->passed.get(ep).retried)
                        .thenComparingLong(ep->passed.get(ep).latencyMs));
                return out;
            } finally { workers.shutdownNow(); }
        }
    }
}
