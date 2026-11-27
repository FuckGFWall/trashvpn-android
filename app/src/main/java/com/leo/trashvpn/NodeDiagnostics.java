package com.leo.trashvpn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import static com.leo.trashvpn.DiagnosticLog.Topic.LATENCY;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Checks all displayed endpoints without reranking or replacing the cache. */
final class NodeDiagnostics {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    static Job current; // main thread
    static Runnable observer; // main thread
    static final class Job {
        final WarpProfile.Protocol protocol;
        final ScanIntensity level;
        final List<String> endpoints;
        final boolean viaVpnService;
        final WarpVpnService.ActiveNode activeNode;
        final AtomicBoolean cancel=new AtomicBoolean();
        final Map<String,Long> results=new LinkedHashMap<>(); // absent = pending; -1 = not verified
        boolean done;
        Job(WarpProfile.Protocol p,ScanIntensity level,List<String> endpoints,boolean connected) {
            this(p,level,endpoints,connected,null);
        }
        Job(WarpProfile.Protocol p,ScanIntensity level,List<String> endpoints,boolean connected,
                WarpVpnService.ActiveNode activeNode) {
            protocol=p; this.level=level; this.endpoints=Collections.unmodifiableList(new ArrayList<>(endpoints));
            viaVpnService=connected;
            this.activeNode=activeNode;
        }
    }
    static void changed() { if(observer!=null) observer.run(); }
    static boolean running() { return current!=null && !current.done; }
    static boolean hasSystemVpn(Context ctx) {
        ConnectivityManager cm=ctx.getSystemService(ConnectivityManager.class);
        Network active=cm.getActiveNetwork();
        NetworkCapabilities capabilities=active==null?null:cm.getNetworkCapabilities(active);
        return capabilities!=null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }
    static void startOffline(Context ctx,Job job) {
        Context app=ctx.getApplicationContext();
        new Thread(()->{
            try(MihomoSession session=new MihomoSession()) {
                NetworkSettings settings=NetworkSettings.load(app);
                HotRoute route=HotRoute.allocate(job.endpoints);
                session.configure(app,route.profile(job.protocol,WarpRegistration.identity(app,job.protocol),settings,false));
                measure(app,job,route,settings.probeMillis(),()->!hasSystemVpn(app));
            } catch(Exception | LinkageError e) {
                DiagnosticLog.warn(app,LATENCY,"Delay check failed: "+e.getClass().getSimpleName());
            } finally { finish(app,job); }
        },"node-connectivity").start();
    }
    static void measure(Context context,Job job,HotRoute route,int timeoutMs,BooleanSupplier pathTrusted) throws InterruptedException {
        ExecutorService pool=Executors.newFixedThreadPool(Math.min(4,job.endpoints.size()));
        try {
            List<Callable<Void>> tasks=new ArrayList<>();
            for(String endpoint:job.endpoints) tasks.add(()->{
                if(job.cancel.get()) return null;
                long started=SystemClock.elapsedRealtime();
                HotRoute.Node node=route.nodes.get(endpoint);
                WarpProbe.Result r=node==null||!pathTrusted.getAsBoolean()?null:probe(node.port,timeoutMs,job.cancel);
                // Keep even instant results visible as in-flight, without adding animation time to RTT.
                long frameTime=280-(SystemClock.elapsedRealtime()-started);
                if(frameTime>0 && !job.cancel.get()) Thread.sleep(frameTime);
                MAIN.post(()->{
                    if(current==job && !job.cancel.get() && !job.done) {
                        long value=r==null||!pathTrusted.getAsBoolean()?-1:Math.max(1,r.elapsedMs);
                        job.results.put(endpoint,value);
                        DiagnosticLog.info(context,LATENCY,"NODE_DELAY "+job.protocol+" "+endpoint+" "+value);
                        changed();
                    }
                });
                return null;
            });
            pool.invokeAll(tasks);
        } finally { pool.shutdownNow(); }
    }
    static WarpProbe.Result probe(int port,int timeout,AtomicBoolean cancel) {
        try {
            WarpProbe.Result r=LocalSelection.trace(port,false,timeout,cancel);
            return !cancel.get() && r.marker.verified()?r:null;
        } catch(IOException e) { return null; }
    }
    static void finish(Context context,Job job) {
        MAIN.post(()->{
            if(current!=job) return;
            if(job.cancel.get()) { current=null; changed(); return; }
            for(String ep:job.endpoints) if(!job.results.containsKey(ep)) job.results.put(ep,-1L);
            job.done=true; changed();
            DiagnosticLog.info(context,LATENCY,"NODE_DELAY_DONE "+job.protocol);
        });
    }
}
