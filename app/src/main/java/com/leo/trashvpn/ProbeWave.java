package com.leo.trashvpn;

import android.content.Context;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One native profile per wave, independent named WARP outbounds; no shared selector or system TUN. */
final class ProbeWave {
    static String profile(WarpProfile.Protocol p,TunnelIdentity id,List<String> eps,List<Integer> ports) {
        return profile(p,id,eps,ports,NetworkSettings.defaults());
    }
    static String profile(WarpProfile.Protocol p,TunnelIdentity id,List<String> eps,List<Integer> ports,NetworkSettings settings) {
        StringBuilder listeners=new StringBuilder("listeners:\n"), proxies=new StringBuilder("proxies:\n");
        if (eps.isEmpty() || eps.size()>10 || eps.size()!=ports.size()) throw new IllegalArgumentException();
        for (int i=0;i<eps.size();i++) {
            String name="candidate-"+i;
            String yaml=WarpProfile.render(p,eps.get(i),id,settings);
            proxies.append(yaml.substring(yaml.indexOf("proxies:\n")+9).replace(WarpProfile.PROXY,name));
            listeners.append("  - name: probe-").append(i).append("\n    type: tunnel\n    listen: 127.0.0.1\n    port: ")
                .append(ports.get(i)).append("\n    network: [tcp]\n    target: www.cloudflare.com:443\n    proxy: ").append(name).append('\n');
        }
        return "mode: rule\nipv6: false\nallow-lan: false\nlog-level: silent\ngeo-auto-update: false\nfind-process-mode: off\nrules: ['MATCH,REJECT']\n"+listeners+proxies;
    }
    static List<String> run(Context ctx,WarpProfile.Protocol p,TunnelIdentity id,List<String> eps,
                            NetworkSettings settings,long deadline,AtomicBoolean stop) throws Exception {
        List<Integer> ports=new ArrayList<>();
        List<ServerSocket> reserved=new ArrayList<>();
        try {
            for (String ignored:eps) {
                ServerSocket s=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1")); reserved.add(s); ports.add(s.getLocalPort());
            }
        } finally { for (ServerSocket s:reserved) s.close(); }
        try (MihomoSession session=new MihomoSession()) {
            session.configure(ctx,profile(p,id,eps,ports,settings));
            int ms=LocalSelection.budgetMillis(settings,deadline);
            if(ms==0) return null;
            ExecutorService workers=Executors.newFixedThreadPool(eps.size());
            try {
                List<Callable<Long>> jobs=new ArrayList<>();
                for (int port:ports) jobs.add(()->{
                    if (stop.get()) return Long.MAX_VALUE;
                    try { return LocalSelection.trace(port,true,ms,stop).elapsedMs; }
                    catch (java.io.IOException e) { return Long.MAX_VALUE; }
                });
                List<Future<Long>> results=workers.invokeAll(jobs,ms+400L,TimeUnit.MILLISECONDS);
                Map<String,Long> times=new LinkedHashMap<>();
                for(int i=0;i<eps.size();i++) if (!results.get(i).isCancelled()) {
                    long elapsed=results.get(i).get();
                    if(elapsed!=Long.MAX_VALUE) times.put(eps.get(i),elapsed);
                }
                List<String> out=new ArrayList<>(times.keySet());
                out.sort(Comparator.comparingLong(times::get));
                return out;
            } finally { workers.shutdownNow(); }
        }
    }
}
