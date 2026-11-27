package com.leo.trashvpn;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import static com.leo.trashvpn.DiagnosticLog.Topic.SELECTION;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded scan in the app process, without a foreground-service notification. */
final class ScanService {
    static final class Job {
        final WarpProfile.Protocol protocol;
        final ScanIntensity level;
        final boolean resample;
        final AtomicBoolean cancel=new AtomicBoolean();
        LocalSelection.Step step=LocalSelection.Step.PREPARE;
        int title=R.string.scan_load_history;
        UiText detail=new UiText(R.string.scan_continue_background);
        boolean done; int found, percent=5;
        Job(WarpProfile.Protocol p,ScanIntensity l) {
            this(p,l,false);
        }
        Job(WarpProfile.Protocol p,ScanIntensity l,boolean resample) {
            protocol=p; level=l; this.resample=resample;
        }
        void progress(LocalSelection.Step next,int title,UiText detail,int percent) {
            // Verification may need more candidates. Show the extra screening within step 3.
            boolean supplementary=next==LocalSelection.Step.SCREEN && step==LocalSelection.Step.VERIFY;
            if(next.ordinal()<step.ordinal() && !supplementary) return;
            if(!supplementary) step=next;
            this.title=supplementary?R.string.scan_supplementary:title;
            this.detail=detail;
            this.percent=Math.max(this.percent,percent);
        }
    }
    // Only accessed on the main looper; workers only read immutable parameters and cancel.
    static Job current;
    static Runnable observer;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private ScanService() { }

    static void notifyUi() { if(observer!=null) observer.run(); }
    static void begin(Context ctx,Job j) {
        if(current!=null && !current.done) return;
        current=j;
        Context app=ctx.getApplicationContext();
        DiagnosticLog.info(app,SELECTION,"Preparing scan "+j.protocol+" / "+j.level);
        new Thread(()->{
            int count=0; boolean error=false;
            try {
                count=LocalSelection.run(app,j.protocol,j.level,j.resample,j.cancel,(step,title,detail,percent)->MAIN.post(()->{
                    if(j.cancel.get() || j.done || j!=current) return;
                    j.progress(step,title,detail,percent);
                    notifyUi();
                })).size();
            } catch(Exception | LinkageError e) {
                error=true;
                if(!j.cancel.get()) DiagnosticLog.warn(app,SELECTION,"Scan ended: "+e.getClass().getSimpleName());
            }
            final int found=count; final boolean failed=error;
            MAIN.post(()->{
                j.found=found; j.done=true; j.title=j.cancel.get()?R.string.scan_paused:failed?R.string.scan_interrupted:
                        found>0?R.string.scan_finished:R.string.scan_no_nodes;
                j.detail=new UiText(R.string.scan_progress_saved);
                DiagnosticLog.info(app,SELECTION,(j.cancel.get()?"PAUSED ":found==0?"FAIL ":"SELECTED ")+j.protocol+" no-tun count="+found);
                notifyUi();
            });
        },"warp-selection").start();
        notifyUi();
    }
}
