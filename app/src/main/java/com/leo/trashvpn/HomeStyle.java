package com.leo.trashvpn;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

final class HomeStyle {
    static final String THEME_PREF="theme", DEFAULT_THEME="system";
    final boolean dark;
    final int background, card, tile, selected, text, muted, orange, tint, track, disabled;
    final int ready, readyText;
    HomeStyle(Context context, String theme) {
        dark = "dark".equals(theme) || (!"light".equals(theme) &&
                (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        background = dark ? 0xff000000 : 0xfff2f2f7;
        card = dark ? 0xff1c1c1e : 0xffffffff;
        tile = dark ? 0xff272729 : 0xfff2f2f7;
        selected = dark ? 0xff38271d : 0xfffff1e6;
        text = dark ? 0xfffafafa : 0xff17171a;
        muted = dark ? 0xffa1a1a9 : 0xff64646d;
        orange = dark ? 0xffff711e : 0xffc54906;
        tint = 0xffff681b;
        track = dark ? 0xff303033 : 0xffe8e8ee;
        disabled = dark ? 0xff646467 : 0xff9c9ca4;
        ready = dark ? 0xff59ce91 : 0xff2ca36a;
        readyText = dark ? ready : 0xff1b7749;
    }

    static GradientDrawable shape(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    @android.annotation.SuppressLint("ViewConstructor") // Programmatic only: kind and palette are required.
    static final class Symbol extends View {
        enum Kind { GEAR, SHIELD, GLOBE, DATACENTER, CHECK, BACK, NEXT, EXTERNAL,
            SHARE, PULSE, GITHUB, TELEGRAM, YOUTUBE, BRAND_X, HEART }
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        final Kind kind;
        final HomeStyle s;
        final int ink;
        private final Drawable gear;
        boolean marked;
        Symbol(Context context,Kind kind,HomeStyle s) { this(context,kind,s,s.muted); }
        Symbol(Context context,Kind kind,HomeStyle s,int ink) {
            super(context); this.kind=kind; this.s=s; this.ink=ink;
            gear=kind==Kind.GEAR?context.getDrawable(R.drawable.ic_settings_outline).mutate():null;
            if(gear!=null) {
                gear.setTint(s.tint);
                gear.setBounds(19,19,81,81);
            }
        }
        void checked(boolean checked) { marked=checked; invalidate(); }
        private void line(Canvas c, float x, float y, float endX, float endY) { c.drawLine(x,y,endX,endY,paint); }
        private void fill(int color) { paint.setStyle(Paint.Style.FILL); paint.setColor(color); }
        private void stroke(int color,float width) {
            paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width);
            paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND);
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float size=Math.min(getWidth(),getHeight());
            c.save(); c.translate((getWidth()-size)/2,(getHeight()-size)/2); c.scale(size/100,size/100);
            switch(kind) {
                case GEAR:
                    gear.draw(c);
                    break;
                case SHIELD:
                    stroke(s.muted,6); path.rewind();
                    path.moveTo(50,10); path.lineTo(80,23); path.lineTo(80,63); path.quadTo(72,80,50,91);
                    path.quadTo(28,80,20,63); path.lineTo(20,23); path.close(); c.drawPath(path,paint);
                    c.drawRoundRect(39,46,61,67,3,3,paint); c.drawArc(42,32,58,53,180,180,false,paint);
                    break;
                case GLOBE:
                    stroke(ink,6);
                    c.drawCircle(50,50,39,paint);
                    c.drawOval(33,11,67,89,paint);
                    line(c,11,50,89,50);
                    break;
                case DATACENTER:
                    stroke(s.muted,6);
                    c.drawRoundRect(12,16,88,43,7,7,paint);
                    c.drawRoundRect(12,57,88,84,7,7,paint);
                    line(c,27,29.5f,29,29.5f); line(c,27,70.5f,29,70.5f);
                    line(c,56,29.5f,73,29.5f); line(c,56,70.5f,73,70.5f);
                    break;
                case CHECK:
                    if(marked) {
                        fill(s.tint); c.drawCircle(50,50,43,paint);
                        stroke(0xff241609,8); line(c,30,51,44,66); line(c,44,66,70,32);
                    } else { stroke(s.muted,6); c.drawCircle(50,50,40,paint); }
                    break;
                case BACK:
                    if(getLayoutDirection()==View.LAYOUT_DIRECTION_RTL) c.scale(-1,1,50,50);
                    stroke(s.tint,5); line(c,58,27,35,50); line(c,35,50,58,73);
                    break;
                case NEXT:
                    if(getLayoutDirection()==View.LAYOUT_DIRECTION_RTL) c.scale(-1,1,50,50);
                    stroke(ink,8); line(c,38,24,64,50); line(c,64,50,38,76);
                    break;
                case EXTERNAL:
                    stroke(ink,7); line(c,25,75,74,26); line(c,38,26,74,26); line(c,74,26,74,62);
                    break;
                case SHARE:
                    c.scale(.78f,.78f,50,50);
                    stroke(ink,5); line(c,31,47,63,29); line(c,31,53,63,71);
                    fill(ink); c.drawCircle(24,50,10,paint); c.drawCircle(70,25,10,paint); c.drawCircle(70,75,10,paint);
                    break;
                case PULSE:
                    stroke(ink,6); path.rewind();
                    path.moveTo(5,54); path.lineTo(27,54); path.lineTo(39,19); path.lineTo(54,80);
                    path.lineTo(67,35); path.lineTo(75,54); path.lineTo(95,54); c.drawPath(path,paint);
                    break;
                case TELEGRAM:
                    fill(ink); path.rewind();
                    path.moveTo(8,44); path.lineTo(91,14); path.quadTo(96,13,94,20); path.lineTo(76,87);
                    path.quadTo(74,93,69,89); path.lineTo(45,70); path.lineTo(33,81); path.lineTo(34,58);
                    path.lineTo(76,30); path.lineTo(29,53); path.lineTo(9,48); path.quadTo(4,46,8,44);
                    path.close(); c.drawPath(path,paint);
                    break;
                case YOUTUBE:
                    fill(ink); c.drawRoundRect(6,17,94,83,14,14,paint);
                    fill(Color.WHITE); path.rewind(); path.moveTo(42,34); path.lineTo(68,50);
                    path.lineTo(42,66); path.close(); c.drawPath(path,paint);
                    break;
                case GITHUB:
                    fill(ink); c.drawCircle(50,50,43,paint);
                    fill(s.dark?0xff303034:0xffe4e4e8); path.rewind();
                    path.moveTo(43,94); path.lineTo(43,77); path.quadTo(29,77,25,67);
                    path.quadTo(21,62,17,59); path.quadTo(25,59,29,66); path.quadTo(33,70,43,68);
                    path.lineTo(45,65); path.quadTo(20,63,22,43); path.quadTo(22,36,28,31);
                    path.quadTo(25,24,28,17); path.quadTo(37,17,43,24); path.quadTo(50,22,57,24);
                    path.quadTo(64,17,72,17); path.quadTo(75,24,72,31); path.quadTo(78,36,78,43);
                    path.quadTo(80,63,55,65); path.quadTo(60,69,59,77); path.lineTo(59,94);
                    path.close(); c.drawPath(path,paint);
                    break;
                case BRAND_X:
                    stroke(ink,4.5f); line(c,82,11,18,89);
                    path.rewind(); path.moveTo(12,11); path.lineTo(32,11); path.lineTo(88,89);
                    path.lineTo(68,89); path.close(); c.drawPath(path,paint);
                    break;
                case HEART:
                    fill(ink); path.rewind(); path.moveTo(50,25);
                    path.cubicTo(22,-1,2,24,11,48); path.cubicTo(17,64,37,81,50,89);
                    path.cubicTo(63,81,83,64,89,48); path.cubicTo(98,24,78,-1,50,25);
                    path.close(); c.drawPath(path,paint);
                    break;
            }
            c.restore();
        }
    }

    @android.annotation.SuppressLint("ViewConstructor") // Programmatic only: the palette is required.
    static final class Track extends View {
        static final int REGISTRATION=25;
        enum Running { NONE, REGISTER, SELECT }
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final HomeStyle s;
        private final Matrix sweepTransform=new Matrix();
        private final LinearGradient sweepGradient=new LinearGradient(-1,0,1,0,
                new int[]{0x00fff8ef,0xeefff8ef,0x00fff8ef},null,Shader.TileMode.CLAMP);
        private float value;
        private float sweepPosition;
        private int target;
        private boolean ready, registered;
        private Running running=Running.NONE;
        private ValueAnimator motion, sweep;
        Track(Context c,HomeStyle s) { super(c); this.s=s; }
        void update(int percent,boolean registered,boolean ready,Running running) {
            this.registered=registered; this.ready=ready;
            if(this.running!=running) stopSweep();
            this.running=running;
            syncSweep(isAttachedToWindow() && getWindowVisibility()==VISIBLE && isShown());
            if(target!=percent) {
                target=percent;
                if(motion!=null) motion.cancel();
                if(!isLaidOut() || !isShown()) value=target;
                else {
                    motion=ValueAnimator.ofFloat(value,target);
                    motion.setDuration(320);
                    motion.setInterpolator(new DecelerateInterpolator());
                    motion.addUpdateListener(a->{ value=(float)a.getAnimatedValue(); invalidate(); });
                    motion.start();
                }
            }
            invalidate();
        }
        private void syncSweep(boolean visible) {
            if(!visible || running==Running.NONE || !ValueAnimator.areAnimatorsEnabled()) {
                stopSweep();
                return;
            }
            if(sweep==null) {
                sweep=ValueAnimator.ofFloat(0,1);
                sweep.setDuration(1800);
                sweep.setRepeatCount(ValueAnimator.INFINITE);
                sweep.setInterpolator(new LinearInterpolator());
                sweep.addUpdateListener(a->{
                    sweepPosition=(float)a.getAnimatedValue();
                    invalidate();
                });
            }
            if(!sweep.isStarted()) {
                sweepPosition=0;
                sweep.start();
            }
        }
        private void stopSweep() {
            if(sweep!=null && sweep.isStarted()) {
                sweep.cancel();
                invalidate();
            }
        }
        @Override public void onVisibilityAggregated(boolean visible) {
            super.onVisibilityAggregated(visible);
            syncSweep(visible);
        }
        @Override protected void onDetachedFromWindow() {
            if(motion!=null) motion.cancel();
            stopSweep();
            value=target;
            super.onDetachedFromWindow();
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float d=getResources().getDisplayMetrics().density, y=getHeight()/2f, r=3*d;
            c.save();
            if(getLayoutDirection()==View.LAYOUT_DIRECTION_RTL) {
                c.translate(getWidth(),0);
                c.scale(-1,1);
            }
            paint.setColor(s.track); c.drawRoundRect(0,y-r,getWidth(),y+r,r,r,paint);
            int active=ready?s.ready:s.tint;
            float milestone=getWidth()*REGISTRATION/100f;
            float end=getWidth()-7*d;
            float x=value<=REGISTRATION ? milestone*value/REGISTRATION
                    : milestone+(end-milestone)*(value-REGISTRATION)/(100-REGISTRATION);
            float thumb=Math.max(6*d,x);
            if(value>0) {
                paint.setColor(active);
                float fillEnd=Math.min(getWidth(),x+5*d);
                c.drawRoundRect(0,y-r,fillEnd,y+r,r,r,paint);
                if(sweep!=null && sweep.isStarted()) drawSweep(c,y,r,milestone,Math.min(thumb,fillEnd));
            }
            dot(c,milestone,y,4.5f*d,registered && value>=REGISTRATION-.1f?active:s.disabled);
            dot(c,end,y,4.5f*d,ready && value>=99.9f?active:s.disabled);
            if(value>0) dot(c,thumb,y,5.5f*d,active);
            c.restore();
        }
        private void drawSweep(Canvas c,float y,float radius,float milestone,float thumb) {
            float stop=running==Running.REGISTER?Math.min(milestone,thumb):thumb;
            if(stop<=0) return;
            float halfBand=Math.min(5.5f*radius,stop*.4f);
            float center=-halfBand+(stop+2*halfBand)*sweepPosition;
            sweepTransform.setScale(halfBand,1);
            sweepTransform.postTranslate(center,0);
            sweepGradient.setLocalMatrix(sweepTransform);
            c.save();
            c.clipRect(0,y-radius,stop,y+radius);
            paint.setColor(Color.WHITE);
            paint.setShader(sweepGradient);
            c.drawRoundRect(0,y-radius,getWidth(),y+radius,radius,radius,paint);
            paint.setShader(null);
            c.restore();
        }
        private void dot(Canvas c,float x,float y,float radius,int color) {
            paint.setColor(s.card); c.drawCircle(x,y,radius+1.5f*getResources().getDisplayMetrics().density,paint);
            paint.setColor(color); c.drawCircle(x,y,radius,paint);
        }
    }
}
