package com.leo.trashvpn;

import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.oviron.libmihomo.Clash;
import io.github.oviron.libmihomo.InvokeInterface;
import io.github.oviron.libmihomo.TunInterface;

final class MihomoSession implements AutoCloseable {
    // JNI core is process-wide. Never let a cancelled Activity scan retire a later VPN profile.
    private static final java.util.concurrent.Semaphore OWNER = new java.util.concurrent.Semaphore(1, true);
    private boolean owned;
    private final Clash core = Clash.INSTANCE;
    private Context context;
    private boolean tunStarted;

    void configure(Context context, String yaml) throws Exception {
        if (!OWNER.tryAcquire(15, TimeUnit.SECONDS)) throw new IOException("Mihomo busy");
        owned = true;
        this.context = context.getApplicationContext();
        applyProfile(context, yaml);
    }

    private void applyProfile(Context context, String yaml) throws Exception {
        core.load(context.getApplicationInfo().nativeLibraryDir);
        core.assertReady();
        File home = new File(context.getCacheDir(), "mihomo");
        if (!home.isDirectory() && !home.mkdirs()) throw new IOException("Cannot create core directory");
        String init = new JSONObject().put("home-dir", home.getAbsolutePath())
                .put("version", Build.VERSION.SDK_INT).toString();

        // This bridge version reads home-dir/config.yaml; serve it from an anonymous
        // pipe via a private symlink rather than write key material to a file.
        byte[] data = yaml.getBytes(StandardCharsets.UTF_8);
        if (data.length > 65536) {
            Arrays.fill(data, (byte) 0);
            throw new IOException("Probe batch exceeds size limit");
        }
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        Thread feeder = new Thread(() -> {
            try (OutputStream writer = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                writer.write(data);
            } catch (IOException ignored) { /* reader closed after setup failure */ }
            finally { Arrays.fill(data, (byte) 0); }
        }, "mihomo-profile");
        try (ParcelFileDescriptor readFd = pipe[0]) {
            feeder.start();
            File profile = new File(home, "config.yaml");
            linkProfile(home, profile, "/proc/self/fd/" + readFd.getFd());
            try {
                CompletableFuture<String> applied = new CompletableFuture<>();
                core.quickSetup(init, "{}", (InvokeInterface) applied::complete);
                String result = applied.get(12, TimeUnit.SECONDS);
                if (result != null && !result.isEmpty()) throw new IOException("Mihomo rejected the profile");
            } finally {
                linkProfile(home, profile, "/dev/null");
            }
        }
    }

    private static void linkProfile(File home, File profile, String target) throws Exception {
        String path = new File(home, "profile-" + UUID.randomUUID() + ".link").getAbsolutePath();
        Os.symlink(target, path);
        Os.rename(path, profile.getAbsolutePath());
    }

    void start(ParcelFileDescriptor tun, TunInterface callbacks, int mtu, String dnsHijack) {
        // Keep the service's original VPN handle; the native bridge owns this duplicate.
        // detachFd transfers responsibility; it must not be closed again by this caller.
        core.startTUN(tun.detachFd(), callbacks, "trashvpn", "gvisor",
                WarpProfile.TUN_ADDRESS + "/" + WarpProfile.PREFIX, dnsHijack, mtu);
        tunStarted = true;
    }

    void select(String node) throws Exception {
        action("changeProxy",new JSONObject().put("group-name",WarpProfile.PROXY).put("proxy-name",node).toString());
    }
    void closeConnections() throws Exception { action("closeAllConnections",JSONObject.NULL); }

    private void action(String name,Object data) throws Exception {
        String id=UUID.randomUUID().toString();
        CompletableFuture<String> result=new CompletableFuture<>();
        core.invokeAction(new JSONObject().put("id",id).put("method",name).put("data",data).toString(),
                (InvokeInterface)result::complete);
        JSONObject response=new JSONObject(result.get(3,TimeUnit.SECONDS));
        if(!id.equals(response.optString("id"))||response.optInt("code",-1)!=0)
            throw new IOException("Mihomo action rejected: "+name);
    }

    @Override
    public void close() throws Exception {
        if (!owned) return;
        try { closeOwned(); } finally { owned = false; OWNER.release(); }
    }

    private void closeOwned() throws Exception {
        try {
            if (tunStarted) {
                tunStarted = false;
                core.stopTun();
            }
        } finally {
            if (context != null) {
                Context app = context;
                context = null;
                try {
                    CompletableFuture<String> closed = new CompletableFuture<>();
                    core.invokeAction("{\"id\":\"shutdown\",\"method\":\"closeAllConnections\",\"data\":null}",
                            (InvokeInterface) closed::complete);
                    closed.get(2, TimeUnit.SECONDS);
                } finally {
                    // Drop active proxy/DNS references so Mihomo can retire old WG/QUIC sockets.
                    applyProfile(app, """
                            log-level: silent
                            ipv6: false
                            geo-auto-update: false
                            rules:
                              - MATCH,REJECT
                            """);
                    core.forceGC();
                }
            }
        }
    }
}
