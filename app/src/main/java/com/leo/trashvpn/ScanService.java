package com.leo.trashvpn;

import android.app.*;
import android.content.*;
import android.os.*;
import static com.leo.trashvpn.DiagnosticLog.Topic.SELECTION;
import java.util.concurrent.atomic.AtomicBoolean;

/** One user-initiated, bounded scan in the application process. Never acquires VPN permission. */
public final class ScanService extends Service {
    static final class Job {
        final WarpProfile.Protocol protocol;
        final ScanIntensity level;
        final AtomicBoolean cancel=new AtomicBoolean();
        int title=R.string.scan_prepare;
        UiText detail=new UiText(R.string.scan_continue_background);
        boolean done; int found, step=1, percent=5;
        Job(WarpProfile.Protocol p,ScanIntensity l) {
            protocol=p; level=l;
        }
    }
    // Only accessed on the main looper; workers only read immutable parameters and cancel.
    static Job current;
    static Runnable observer;
    private static ScanService foreground;
    static void notifyUi() { if(observer!=null) observer.run(); }
    static void refreshNotificationLanguage() {
        if(foreground!=null && foreground.running!=null) {
            NotificationManager manager=foreground.getSystemService(NotificationManager.class);
            manager.notify(412,foreground.notice(foreground.running,manager));
        }
    }
    static void begin(Context ctx,Job j) {
        if(current!=null && !current.done) return;
        current=j;
        DiagnosticLog.info(ctx,SELECTION,"Preparing scan "+j.protocol+" / "+j.level);
        try { ctx.startForegroundService(new Intent(ctx,ScanService.class)); }
        catch(RuntimeException e) {
            j.done=true; j.title=R.string.scan_blocked;
            DiagnosticLog.warn(ctx,SELECTION,"Background scan blocked · "+e.getClass().getSimpleName());
        }
        notifyUi();
    }
    final Handler main=new Handler(Looper.getMainLooper());
    private Job running;
    private Notification notice(Job job,NotificationManager nm) {
        Context language=AppLanguage.wrap(this);
        nm.createNotificationChannel(new NotificationChannel("endpoint-scan",
                language.getString(R.string.scan_channel),NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,50,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause=PendingIntent.getService(this,51,new Intent(this,ScanService.class).setAction("PAUSE"),
            PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"endpoint-scan").setSmallIcon(R.drawable.ic_warp)
            .setContentTitle(language.getString(R.string.step_format,job.step,language.getString(job.title)))
            .setContentText(job.detail.get(language)).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(new Notification.Action.Builder(null,language.getString(R.string.pause),pause).build()).build();
    }
    @Override public int onStartCommand(Intent i,int flags,int startId) {
        if(i!=null && "PAUSE".equals(i.getAction())) {
            if(running!=null) { running.cancel.set(true); notifyUi(); } else stopSelf();
            return START_NOT_STICKY;
        }
        if(running!=null) return START_NOT_STICKY;
        Job j=current;
        if(j==null || j.done) { stopSelf(); return START_NOT_STICKY; }
        running=j;
        NotificationManager nm=getSystemService(NotificationManager.class);
        try { startForeground(412,notice(j,nm)); foreground=this; }
        catch(RuntimeException e) {
            j.done=true; j.title=R.string.scan_blocked;
            DiagnosticLog.warn(this,SELECTION,"Background scan blocked · "+e.getClass().getSimpleName());
            stopSelf(); notifyUi(); return START_NOT_STICKY;
        }
        new Thread(()->{
            int count=0; boolean error=false;
            try {
                count=LocalSelection.run(this,j.protocol,j.level,j.cancel,(step,title,detail,percent)->main.post(()->{
                    if(j.cancel.get() || j.done || j!=current) return;
                    j.title=title;j.detail=detail;j.step=step;j.percent=Math.max(percent,j.percent);
                    nm.notify(412,notice(j,nm));notifyUi();
                })).size();
            } catch(Exception | LinkageError e) {
                error=true;
                if(!j.cancel.get()) DiagnosticLog.warn(this,SELECTION,"Scan ended: "+e.getClass().getSimpleName());
            }
            final int found=count; final boolean failed=error;
            main.post(()->{
                j.found=found; j.done=true; j.title=j.cancel.get()?R.string.scan_paused:failed?R.string.scan_interrupted:
                        found>0?R.string.scan_finished:R.string.scan_no_nodes;
                j.detail=new UiText(R.string.scan_progress_saved);
                DiagnosticLog.info(this,SELECTION,(j.cancel.get()?"PAUSED ":found==0?"FAIL ":"SELECTED ")+j.protocol+" no-tun count="+found);
                if(foreground==this) foreground=null;
                stopForeground(STOP_FOREGROUND_REMOVE); running=null; stopSelf(); notifyUi();
            });
        },"warp-selection").start();
        return START_NOT_STICKY;
    }
    @Override public void onTimeout(int startId) { if(running!=null) running.cancel.set(true); stopSelf(); }
    @Override public void onDestroy() {
        if(foreground==this) foreground=null;
        if(running!=null) running.cancel.set(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
