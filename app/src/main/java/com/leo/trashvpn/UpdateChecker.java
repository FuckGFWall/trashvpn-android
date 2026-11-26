package com.leo.trashvpn;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/** All routing decisions/state live on the main thread; only fixed public HTTPS requests run off-thread. */
final class UpdateChecker {
    static final String DOWNLOAD="https://itmanager.top/download.html";
    private static final String API="https://api.github.com/repos/FuckGFWall/trashvpn-android/releases/latest";
    private static final long CHECK_INTERVAL=TimeUnit.HOURS.toMillis(4);
    private static UpdateChecker instance;
    private final Context app;
    private final ConnectivityManager connectivity;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final UpdateCycle<Network> cycle=new UpdateCycle<>();
    private final Set<Runnable> observers=new LinkedHashSet<>();
    private long nextCheck;
    private String latest;

    private UpdateChecker(Context context) {
        app=context.getApplicationContext();
        connectivity=app.getSystemService(ConnectivityManager.class);
    }

    static UpdateChecker get(Context context) {
        if(instance==null) instance=new UpdateChecker(context);
        return instance;
    }
    static void vpnChanged(Network verifiedTunnel) {
        if(instance!=null) {
            instance.cycle.network(verifiedTunnel);
            instance.retry();
        }
    }
    void observe(Runnable observer) { observers.add(observer); }
    void unobserve(Runnable observer) { observers.remove(observer); }
    String availableTag() { return latest; }

    void onForeground(Network verifiedTunnel) {
        cycle.network(verifiedTunnel);
        long now=SystemClock.elapsedRealtime();
        if(now>=nextCheck && cycle.begin()) {
            nextCheck=now+CHECK_INTERVAL;
            request(null,verifiedTunnel!=null);
        } else retry();
    }

    private void retry() {
        Network tunnel=cycle.nextVpn();
        if(tunnel!=null) request(tunnel,false);
    }

    private void request(Network verifiedTunnel,boolean allowUnderlying) {
        io.execute(()->{
            try {
                Network route=verifiedTunnel!=null?verifiedTunnel:directNetwork(allowUnderlying);
                NetworkCapabilities caps=route==null?null:connectivity.getNetworkCapabilities(route);
                if(caps==null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)!=(verifiedTunnel!=null))
                    throw new IOException("Required network unavailable");
                String tag=ReleaseVersion.newerTag(installedVersion(),fetch(route));
                main.post(()->finished(true,tag));
            } catch(IOException | RuntimeException e) {
                Log.d("TrashVPN","Update check "+(verifiedTunnel==null?"direct":"VPN")+" failed: "+e.getClass().getSimpleName());
                main.post(()->finished(false,null));
            }
        });
    }

    private void finished(boolean success,String tag) {
        cycle.completed(success);
        if(success && !java.util.Objects.equals(latest,tag)) {
            latest=tag;
            for(Runnable observer:observers.toArray(new Runnable[0])) observer.run();
        }
        retry();
    }

    @SuppressWarnings("deprecation") // Look up an underlying network only if our own VPN is already verified.
    private Network directNetwork(boolean allowUnderlying) {
        Network active=connectivity.getActiveNetwork();
        if(active!=null && direct(connectivity.getNetworkCapabilities(active))) return active;
        // Do not bypass a VPN belonging to another application.
        if(!allowUnderlying) return null;
        for(Network network:connectivity.getAllNetworks())
            if(direct(connectivity.getNetworkCapabilities(network))) return network;
        return null;
    }
    private static boolean direct(NetworkCapabilities caps) {
        return caps!=null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private String installedVersion() throws IOException {
        try { return app.getPackageManager().getPackageInfo(app.getPackageName(),0).versionName; }
        catch(PackageManager.NameNotFoundException e) { throw new IOException("Version unavailable",e); }
    }

    private static String fetch(Network network) throws IOException {
        HttpsURLConnection connection=(HttpsURLConnection)network.openConnection(new URL(API),Proxy.NO_PROXY);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept","application/vnd.github+json");
            connection.setRequestProperty("User-Agent","TrashVPN-Android");
            if(connection.getResponseCode()!=200) throw new IOException("GitHub release unavailable");
            try(InputStream in=connection.getInputStream()) {
                return new String(KeyStoreFile.readBounded(in,256*1024),StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
}
