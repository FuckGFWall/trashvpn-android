package com.leo.trashvpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import static com.leo.trashvpn.DiagnosticLog.Topic.CONNECTION;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.oviron.libmihomo.TunInterface;

public final class WarpVpnService extends VpnService {
    static final String START = "com.leo.trashvpn.START";
    static final String STOP = "com.leo.trashvpn.STOP";
    static final String SWITCH = "com.leo.trashvpn.SWITCH";
    static final String DELAY = "com.leo.trashvpn.DELAY";
    static final String PROTOCOL = "protocol";
    static final String CANDIDATE = "candidate";
    static final String INTENSITY = "intensity";
    static final String SCAN_IF_STALE = "scan-if-stale";
    static final String FIXED_ENDPOINT = "fixed-endpoint";
    private static final String CHANNEL = "warp-tunnel";
    private static final int NOTIFICATION = 410;

    enum Stage { IDLE, STARTING, VERIFYING, CONNECTED, SWITCHING, STOPPING, FAILED, NEEDS_SCAN }
    interface Observer { void changed(Status status); }
    record ActiveNode(WarpProfile.Protocol protocol,String endpoint,boolean automatic) { }
    static final class Status {
        final Stage stage;
        final UiText title;
        final UiText detail;
        final ScanService.Job retryScan;
        final ExitLocation location;
        final ActiveNode node;
        final Network network;

        Status(Stage stage,int title,int detail) {
            this(stage,title,detail,null);
        }

        Status(Stage stage,int title,int detail,ScanService.Job retryScan) {
            this(stage,new UiText(title),detail,retryScan,ExitLocation.UNKNOWN,null,null);
        }

        private Status(Stage stage,UiText title,int detail,ScanService.Job retryScan,ExitLocation location,ActiveNode node,Network network) {
            this.stage = stage;
            this.title = title;
            this.detail = new UiText(detail);
            this.retryScan = retryScan;
            this.location = location;
            this.node = node;
            this.network=network;
        }

        static Status connected(ExitLocation location,ActiveNode node) {
            return connected(location,node,null);
        }
        static Status connected(ExitLocation location,ActiveNode node,Network network) {
            return new Status(Stage.CONNECTED,new UiText(R.string.connected),R.string.vpn_verified,null,location,node,network);
        }
        Status switchingNode() {
            return new Status(Stage.SWITCHING,new UiText(R.string.switching_node),R.string.checking_target,null,location,node,network);
        }
        Status switchRejected() {
            return new Status(Stage.CONNECTED,new UiText(R.string.connected),R.string.switch_rejected,null,location,node,network);
        }

        boolean busy() {
            return stage == Stage.STARTING || stage == Stage.VERIFYING
                    || stage == Stage.CONNECTED || stage == Stage.SWITCHING || stage == Stage.STOPPING;
        }
    }

    private static final ArrayList<Observer> observers = new ArrayList<>(); // main thread
    private static Status status = idle();
    private static WarpVpnService foreground; // main thread
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancel = new AtomicBoolean();
    private final AtomicBoolean protectFailed = new AtomicBoolean();
    private boolean active; // main thread
    private WarpProfile.Protocol activeProtocol;
    private MihomoSession session; // worker thread
    private ParcelFileDescriptor vpn; // worker thread
    private NetworkSettings networkSettings; // worker thread, immutable for this attempt
    private int tunnelMtu;
    private ScanIntensity activeLevel;
    private HotRoute route;
    private String selectedEndpoint;
    private ExitLocation selectedLocation=ExitLocation.UNKNOWN;

    static void observe(Observer observer) {
        observers.add(observer);
        observer.changed(status);
    }

    static void unobserve(Observer observer) { observers.remove(observer); }
    static void refreshNotificationLanguage() {
        if(foreground!=null && foreground.active) foreground.refreshNotification();
    }
    static void acknowledgeScan(Status handled) { if(status==handled) status=idle(); }

    private static Status idle() {
        return new Status(Stage.IDLE,R.string.disconnected,R.string.vpn_idle_hint);
    }

    private static void publish(Status next) {
        status = next;
        UpdateChecker.vpnChanged(next.stage==Stage.CONNECTED?next.network:null);
        for (Observer observer : new ArrayList<>(observers)) observer.changed(next);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            requestStop();
            return START_NOT_STICKY;
        }
        if(SWITCH.equals(intent.getAction())) {
            switchNode(intent);
            return START_NOT_STICKY;
        }
        if(DELAY.equals(intent.getAction())) {
            delayNodes();
            return START_NOT_STICKY;
        }
        if (!START.equals(intent.getAction()) || active) {
            if (!active) stopSelf(startId);
            return START_NOT_STICKY;
        }
        WarpProfile.Protocol protocol;
        int candidate = intent.getIntExtra(CANDIDATE, Integer.MIN_VALUE);
        String fixed = intent.getStringExtra(FIXED_ENDPOINT);
        try {
            protocol = WarpProfile.Protocol.valueOf(intent.getStringExtra(PROTOCOL));
            if (fixed != null ? !NodeSelection.allowed(protocol,fixed)
                    : candidate < NodeSelection.AUTO || candidate >= protocol.endpoints.size()) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException e) {
            DiagnosticLog.warn(this,CONNECTION,"Startup failed: invalid protocol or endpoint");
            publish(new Status(Stage.FAILED,R.string.vpn_start_failed,R.string.vpn_node_missing));
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (VpnService.prepare(this) != null) {
            DiagnosticLog.warn(this,CONNECTION,"Startup failed: no VPN permission");
            publish(new Status(Stage.FAILED,R.string.vpn_no_permission,R.string.vpn_grant_permission));
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        active = true;
        activeProtocol=protocol;
        cancel.set(false);
        protectFailed.set(false);
        String endpoint = fixed != null ? fixed : candidate < 0 ? null : protocol.endpoints.get(candidate);
        DiagnosticLog.info(this,CONNECTION,"Starting "+protocol+" / "+(endpoint==null?"auto":endpoint));
        Status starting=new Status(Stage.STARTING,R.string.vpn_starting,R.string.vpn_prepare);
        publish(starting);
        ScanIntensity level = ScanIntensity.parse(intent.getStringExtra(INTENSITY));
        worker.execute(() -> connect(protocol, endpoint, level,
                intent.getBooleanExtra(SCAN_IF_STALE,false)));
        return START_NOT_STICKY;
    }

    private void connect(WarpProfile.Protocol protocol, String endpoint, ScanIntensity level, boolean scanIfStale) {
        String phase = "credentials";
        try {
            networkSettings=NetworkSettings.load(this);
            activeLevel=level;
            tunnelMtu=networkSettings.effectiveMtu(linkMtu());
            TunnelIdentity identity = WarpRegistration.identity(this,protocol);
            phase = "verification";
            if (endpoint != null) {
                if (establishAndCheck(protocol,endpoint,identity,new UiText(R.string.vpn_verify_connection),false)) return;
            } else if (select(protocol, identity, level)) {
                return;
            } else if (scanIfStale) {
                main.post(() -> {
                    if (active && !cancel.get()) {
                        DiagnosticLog.info(this,CONNECTION,"No cached node verified; rescan "+protocol);
                        end(new Status(Stage.NEEDS_SCAN,R.string.vpn_cache_exhausted,0,new ScanService.Job(protocol,level)));
                    }
                });
                return;
            }
            throw new IOException("No verified endpoint");
        } catch (Exception | LinkageError e) {
            if (cancel.get()) return;
            DiagnosticLog.warn(this,CONNECTION, "FAIL " + protocol + " " + phase + " " + e.getClass().getSimpleName());
            closeTunnel();
            int detail=phase.equals("credentials")?R.string.registration_required:R.string.vpn_failed_detail;
            main.post(() -> {
                if (active && !cancel.get()) end(new Status(Stage.FAILED,R.string.vpn_validation_failed,detail));
            });
        }
    }

    private boolean select(WarpProfile.Protocol p, TunnelIdentity id,
                           ScanIntensity level) throws Exception {
        SelectionCache cache = new SelectionCache(this, p, level);
        return RankedFallback.connect(cache.load(), (endpoint, position, total) -> {
            checkCancel();
            DiagnosticLog.info(this,CONNECTION,"CACHE_TRY "+p+" "+position+"/"+total+" "+endpoint);
            boolean ok=establishAndCheck(p,endpoint,id,new UiText(R.string.cached_node,position,total),true);
            checkCancel();
            return ok;
        },cache::save);
    }

    private void checkCancel() throws InterruptedException {
        if (cancel.get()) throw new InterruptedException();
    }

    private WarpProbe.Result trace() throws InterruptedException {
        checkCancel();
        try {
            WarpProbe.Result r = WarpProbe.verify(this,cancel,networkSettings.noDns);
            checkCancel();
            if (!r.marker.verified() || protectFailed.get())
                DiagnosticLog.info(this,CONNECTION, "PROBE rejected: " + r.marker.name() + " protect=" + !protectFailed.get());
            return r.marker.verified() && !protectFailed.get() ? r : null;
        } catch (IOException e) {
            checkCancel();
            DiagnosticLog.info(this,CONNECTION, "PROBE failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private boolean establishAndCheck(WarpProfile.Protocol p,String ep,TunnelIdentity id,UiText step,boolean automatic) throws Exception {
        checkCancel();
        progress(new Status(Stage.VERIFYING,step,R.string.vpn_verify_exit,null,ExitLocation.UNKNOWN,null,null));
        openTunnel(p, ep, id);
        WarpProbe.Result r = trace();
        if (r == null) { Thread.sleep(400); r = trace(); }
        if (r == null) { closeTunnel(); return false; }
        String marker = r.marker == TraceResult.PLUS ? "plus" : "on";
        DiagnosticLog.info(this,CONNECTION, "PASS " + p + " " + ep + " warp=" + marker + " " + r.elapsedMs + "ms");
        selectedEndpoint=ep;
        selectedLocation=r.location;
        progress(Status.connected(r.location,new ActiveNode(p,ep,automatic),r.verifiedNetwork));
        return true;
    }

    private void openTunnel(WarpProfile.Protocol p, String ep, TunnelIdentity id) throws Exception {
        checkCancel();
        protectFailed.set(false);
        session = new MihomoSession();
        String pinned=getSharedPreferences(NetworkSettings.PREFS,MODE_PRIVATE).getString(HotRoute.fixedKey(p),null);
        route=HotRoute.allocate(HotRoute.sessionEndpoints(p,ep,pinned,new SelectionCache(this,p,activeLevel).load()));
        session.configure(this,route.profile(p,id,networkSettings,true));
        session.select(route.nodes.get(ep).name);
        checkCancel();
        Builder builder = new Builder().setSession("TrashVPN · " + p).setConfigureIntent(openApp())
                .setMtu(tunnelMtu).addAddress(WarpProfile.TUN_ADDRESS, WarpProfile.PREFIX)
                .addRoute("0.0.0.0", 0).setBlocking(false);
        String advertised=networkSettings.advertisedDns();
        if(advertised!=null) builder.addDnsServer(advertised);
        DiagnosticLog.info(this,CONNECTION, "TUN_CONNECT " + p + " " + ep);
        vpn = builder.establish();
        if (vpn == null) throw new IOException("VPN permission revoked");
        try (ParcelFileDescriptor engineFd = vpn.dup()) {
            session.start(engineFd, new TunInterface() {
                @Override public void protect(int fd) {
                    if (!WarpVpnService.this.protect(fd)) protectFailed.set(true);
                }
                @Override public String resolverProcess(int proto, String source, String target, int uid) { return ""; }
            },tunnelMtu,networkSettings.dnsHijack());
        }
    }

    private void delayNodes() {
        NodeDiagnostics.Job job=NodeDiagnostics.current;
        if(job==null || job.done || !job.viaVpnService) return;
        if(!active || status.stage!=Stage.CONNECTED || job.protocol!=activeProtocol) {
            NodeDiagnostics.finish(this,job);
            return;
        }
        worker.execute(()->{
            // Mihomo's native socket hook must protect candidate sockets from looping into this VPN.
            try { NodeDiagnostics.measure(this,job,route,networkSettings.probeMillis(),()->!protectFailed.get()); }
            catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
            finally { NodeDiagnostics.finish(this,job); }
        });
    }

    private void switchNode(Intent intent) {
        if(!active || status.stage!=Stage.CONNECTED || NodeDiagnostics.running()
                || !activeProtocol.name().equals(intent.getStringExtra(PROTOCOL))) return;
        String target=intent.getStringExtra(FIXED_ENDPOINT);
        WarpProfile.Protocol p=activeProtocol;
        if(target!=null && !NodeSelection.allowed(p,target)) return;
        Status original=status;
        publish(original.switchingNode());
        worker.execute(()->{
            String previous=selectedEndpoint;
            String verified=null;
            WarpProbe.Result ok=null;
            try {
                java.util.List<String> candidates=target!=null?java.util.Collections.singletonList(target)
                        : HotRoute.endpoints(p,null,new SelectionCache(this,p,activeLevel).load(),false);
                if(candidates.isEmpty()) candidates=java.util.Collections.singletonList(previous);
                for(int i=0;i<Math.min(candidates.size(),RankedFallback.LIMIT);i++) {
                    checkCancel();
                    String ep=candidates.get(i);
                    if(ep.equals(previous)) { verified=ep; break; }
                    HotRoute.Node node=route.nodes.get(ep);
                    if(node==null) continue;
                    ok=NodeDiagnostics.probe(node.port,networkSettings.probeMillis(),cancel);
                    if(ok!=null) { verified=ep; break; }
                }
                checkCancel();
                if(verified==null) throw new IOException("Target not verified");
                if(!verified.equals(previous)) {
                    try { session.select(route.nodes.get(verified).name); }
                    catch(Exception e) {
                        session.select(route.nodes.get(previous).name);
                        throw e;
                    }
                    selectedEndpoint=verified;
                    selectedLocation=ok.location;
                    // New streams follow the new selection; existing streams must reconnect.
                    try { session.closeConnections(); }
                    catch(Exception e) { DiagnosticLog.warn(this,CONNECTION,"Old streams could not be closed"); }
                }
                DiagnosticLog.info(this,CONNECTION,"HOT_SWITCH "+p+" "+previous+" -> "+verified);
                Status updated=Status.connected(selectedLocation,new ActiveNode(p,verified,target==null),original.network);
                main.post(()->{
                    if(!active || cancel.get()) return;
                    getSharedPreferences(NetworkSettings.PREFS,MODE_PRIVATE).edit()
                            .putString(HotRoute.fixedKey(p),target).apply();
                    publish(updated);
                });
            } catch(Exception | LinkageError e) {
                if(!cancel.get()) {
                    DiagnosticLog.info(this,CONNECTION,"HOT_SWITCH_FAILED "+p+" "+e.getClass().getSimpleName());
                    progress(original.switchRejected());
                }
            }
        });
    }

    private int linkMtu() {
        if(Build.VERSION.SDK_INT<29) return 0;
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);
        Network network=cm.getActiveNetwork();
        LinkProperties link=network==null?null:cm.getLinkProperties(network);
        return link==null?0:link.getMtu();
    }

    private void progress(Status next) {
        main.post(() -> {
            if (!active || cancel.get()) return;
            if(next.stage==Stage.CONNECTED && foreground!=this) {
                try {
                    // Started as a regular service from the visible activity; only a verified tunnel stays foreground.
                    startForeground(NOTIFICATION,notification(next));
                    foreground=this;
                } catch(RuntimeException e) {
                    DiagnosticLog.warn(this,CONNECTION,"Foreground VPN start blocked · "+e.getClass().getSimpleName());
                    requestStop(new Status(Stage.FAILED,R.string.vpn_start_failed,R.string.vpn_launch_blocked));
                    return;
                }
            }
            publish(next);
        });
    }

    private void refreshNotification() {
        if(status.stage==Stage.CONNECTED || status.stage==Stage.SWITCHING)
            getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(status));
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification notification(Status next) {
        Context language=AppLanguage.wrap(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL,language.getString(R.string.vpn_channel),NotificationManager.IMPORTANCE_LOW));
        PendingIntent disconnect = PendingIntent.getService(this, 2, new Intent(this, WarpVpnService.class)
                .setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_warp)
                .setContentTitle(next.title.get(language))
                .setContentText(next.detail.get(language).replace('\n', ' '))
                .setContentIntent(openApp())
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null,language.getString(R.string.disconnect),disconnect).build())
                .build();
    }

    private void closeTunnel() {
        try {
            if (session != null) session.close();
        } catch (Exception | LinkageError ignored) {
            DiagnosticLog.warn(this,CONNECTION, "Mihomo cleanup failed");
        } finally {
            session = null;
            route = null;
            if (vpn != null) {
                try { vpn.close(); } catch (IOException ignored) { }
                vpn = null;
            }
        }
    }

    private void requestStop() { requestStop(idle()); }

    private void requestStop(Status terminal) {
        if (!active) {
            stopSelf();
            return;
        }
        if (cancel.getAndSet(true)) return;
        NodeDiagnostics.Job job=NodeDiagnostics.current;
        if(job!=null && !job.done && job.viaVpnService) job.cancel.set(true);
        publish(new Status(Stage.STOPPING,R.string.vpn_stopping,R.string.vpn_closing));
        worker.execute(() -> {
            closeTunnel();
            main.post(() -> {
                if (active) {
                    DiagnosticLog.info(this,CONNECTION, "STOPPED");
                    end(terminal);
                }
            });
        });
    }

    private void end(Status next) {
        active = false;
        if(foreground==this) foreground=null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        publish(next);
    }

    @Override public void onRevoke() { main.post(this::requestStop); }

    @Override public void onDestroy() {
        if(foreground==this) foreground=null;
        cancel.set(true);
        NodeDiagnostics.Job job=NodeDiagnostics.current;
        if(job!=null && !job.done && job.viaVpnService) job.cancel.set(true);
        if (active) {
            active = false;
            publish(idle());
        }
        worker.execute(this::closeTunnel);
        worker.shutdown();
        super.onDestroy();
    }
}
