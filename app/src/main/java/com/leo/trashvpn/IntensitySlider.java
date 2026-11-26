package com.leo.trashvpn;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;

import java.util.function.Consumer;

/** Uses continuously spaced touch positions, but only persists one of the three scan levels. */
@android.annotation.SuppressLint("ViewConstructor")
final class IntensitySlider extends SeekBar {
    private static final int STEP=100;
    private static final ScanIntensity[] LEVELS=ScanIntensity.values();
    private final Consumer<ScanIntensity> preview, commit;
    private ScanIntensity selected;
    private boolean tracking, cancelled;

    IntensitySlider(Context context,ScanIntensity initial,Consumer<ScanIntensity> preview,Consumer<ScanIntensity> commit) {
        super(context);
        this.preview=preview;
        this.commit=commit;
        selected=initial;
        setSaveEnabled(false); // A transient drag must not override the saved preference after recreation.
        setMax(STEP*(LEVELS.length-1));
        setKeyProgressIncrement(STEP);
        select(initial);
        setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) {
                if(!fromUser) return;
                ScanIntensity nearest=nearestLevel(progress);
                if(tracking) showPreview(nearest);
                else select(nearest); // Keyboard and direct accessibility adjustment.
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { tracking=true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                tracking=false;
                select(cancelled?selected:nearestLevel(getProgress()));
            }
        });
    }

    void select(ScanIntensity level) {
        boolean changed=selected!=level;
        selected=level;
        setProgress(STEP*level.ordinal(),isLaidOut());
        showPreview(level);
        if(changed) commit.accept(level);
    }

    private static ScanIntensity nearestLevel(int progress) {
        return LEVELS[(progress+STEP/2)/STEP];
    }

    private void showPreview(ScanIntensity level) {
        if(Build.VERSION.SDK_INT>=30) setStateDescription(getContext().getString(level.label));
        preview.accept(level);
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility") // Native SeekBar still handles the gesture and accessibility.
    @Override public boolean onTouchEvent(MotionEvent event) {
        cancelled=event.getActionMasked()==MotionEvent.ACTION_CANCEL;
        try { return super.onTouchEvent(event); }
        finally { cancelled=false; }
    }

    @Override public void setEnabled(boolean enabled) {
        if(!enabled && tracking) {
            long now=SystemClock.uptimeMillis();
            MotionEvent end=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0,0,0);
            try { onTouchEvent(end); }
            finally { end.recycle(); }
        }
        super.setEnabled(enabled);
    }

    @Override public boolean performAccessibilityAction(int action,Bundle arguments) {
        if(isEnabled() && (action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            int index=selected.ordinal()+(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1);
            if(index<0 || index>=LEVELS.length) return false;
            select(LEVELS[index]);
            return true;
        }
        return super.performAccessibilityAction(action,arguments);
    }
}
