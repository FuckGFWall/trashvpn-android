package com.leo.trashvpn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

final class WarpProbe {
    static final class Result {
        final TraceResult marker;
        final long elapsedMs;
        final ExitLocation location;
        final Network verifiedNetwork;
        Result(TraceResult marker, long elapsedMs) {
            this(marker,elapsedMs,ExitLocation.UNKNOWN,null);
        }
        private Result(TraceResult marker,long elapsedMs,ExitLocation location,Network verifiedNetwork) {
            this.marker = marker;
            this.elapsedMs = elapsedMs;
            this.location = location;
            this.verifiedNetwork=verifiedNetwork;
        }
        static Result fromTrace(String body,long elapsedMs) {
            return fromTrace(body,elapsedMs,null);
        }
        private static Result fromTrace(String body,long elapsedMs,Network verifiedNetwork) {
            TraceResult marker=TraceResult.parse(body);
            boolean verified=marker.verified();
            return new Result(marker,elapsedMs,verified?ExitLocation.parse(body):ExitLocation.UNKNOWN,verified?verifiedNetwork:null);
        }
    }

    static Result verify(Context context, AtomicBoolean stop, boolean withoutVpnDns) throws IOException, InterruptedException {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        long deadline = SystemClock.elapsedRealtime() + 2500;
        Network network = null;
        while (!stop.get() && SystemClock.elapsedRealtime() < deadline) {
            Network active = cm.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                network = active;
                break;
            }
            Thread.sleep(100);
        }
        if (network == null || stop.get()) throw new IOException("VPN network is not active");

        long start = SystemClock.elapsedRealtime();
        // In no-DNS mode only confirm the tunnel itself. Don't fail the WARP check merely
        // because the current default resolver is unavailable through the VPN.
        URL trace = new URL(withoutVpnDns ? "https://1.1.1.1/cdn-cgi/trace"
                : "https://www.cloudflare.com/cdn-cgi/trace");
        // Bind DNS and TCP to the VPN Network, not to whatever default network
        // might take over if the Android VPN is revoked mid-request.
        HttpsURLConnection connection = (HttpsURLConnection) network.openConnection(trace, Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Connection", "close");
            if (connection.getResponseCode() != 200) throw new IOException("Unexpected trace HTTP status");
            try (InputStream input = connection.getInputStream()) {
                String body = new String(KeyStoreFile.readBounded(input), StandardCharsets.UTF_8);
                return Result.fromTrace(body,SystemClock.elapsedRealtime()-start,network);
            }
        } finally {
            connection.disconnect();
        }
    }
}
