package com.leo.trashvpn;

import android.content.Context;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
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
        float density=getResources().getDisplayMetrics().density;
        setProgressDrawable(track(Math.round(5*density)));
        int diameter=Math.round(22*density);
        GradientDrawable thumb=new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setSize(diameter,diameter);
        thumb.setColor(0xffffffff); // The hosting theme applies the orange thumb tint.
        setThumb(thumb);
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

    @android.annotation.SuppressLint("RtlHardcoded") // SeekBar mirrors the entire drawable in RTL.
    private static LayerDrawable track(int height) {
        GradientDrawable background=trackLine(height);
        ClipDrawable fill=new ClipDrawable(trackLine(height),Gravity.LEFT,ClipDrawable.HORIZONTAL);
        LayerDrawable track=new LayerDrawable(new Drawable[]{background,fill});
        track.setId(0,android.R.id.background);
        track.setId(1,android.R.id.progress);
        for(int layer=0;layer<2;layer++) {
            track.setLayerHeight(layer,height);
            track.setLayerGravity(layer,Gravity.FILL_HORIZONTAL|Gravity.CENTER_VERTICAL);
        }
        return track;
    }

    private static GradientDrawable trackLine(int height) {
        GradientDrawable line=new GradientDrawable();
        line.setCornerRadius(height/2f);
        line.setColor(0xffffffff); // The host applies the existing active/inactive tint.
        return line;
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
