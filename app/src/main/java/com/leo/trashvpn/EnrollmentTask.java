package com.leo.trashvpn;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** Registration state survives Activity recreation; private response material never enters the UI. */
final class EnrollmentTask {
    enum Action { CONNECT, SCAN, RESCAN }
    static EnrollmentTask current;
    static Runnable observer;
    final boolean masque;
    final Action action;
    final String endpoint;
    final WarpProfile.Protocol protocol;
    final ScanIntensity level;
    WarpRegistration.Step step=WarpRegistration.Step.DEVICE;
    int title=R.string.registration_prepare;
    UiText error;
    boolean done;

    EnrollmentTask(boolean masque, Action action, String endpoint, WarpProfile.Protocol protocol, ScanIntensity level) {
        this.masque=masque; this.action=action; this.endpoint=endpoint; this.protocol=protocol; this.level=level;
    }
    private static void notifyUi() { if(observer!=null) observer.run(); }
    static void start(Context context,EnrollmentTask task) {
        if(current!=null && !current.done) return;
        current=task;
        Context app=context.getApplicationContext();
        Handler main=new Handler(Looper.getMainLooper());
        new Thread(()->{
            UiText problem=null;
            try {
                WarpRegistration.register(app,task.masque,(step,title)->main.post(()->{
                    task.step=step; task.title=title; notifyUi();
                }));
            } catch(WarpRegistration.StatusError e) {
                problem=new UiText(e.status==429?R.string.registration_rate:R.string.registration_http,e.status);
            } catch(javax.net.ssl.SSLException e) {
                problem=new UiText(R.string.registration_tls);
            } catch(java.net.SocketTimeoutException e) {
                problem=new UiText(R.string.registration_timed_out);
            } catch(Exception e) {
                problem=new UiText(R.string.registration_retry);
            }
            final UiText error=problem;
            main.post(()->{
                task.error=error; task.done=true; notifyUi();
            });
        },"warp-registration").start();
        notifyUi();
    }
}
