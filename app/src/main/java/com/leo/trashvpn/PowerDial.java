package com.leo.trashvpn;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.concurrent.ThreadLocalRandom;

/** The liquid stops short of full until the VPN service verifies the connection. */
@android.annotation.SuppressLint("ViewConstructor")
final class PowerDial extends View {
    enum State { IDLE, CONNECTING, CONNECTED }

    private static final float RADIUS=38, MAX_PENDING=0.68f;
    private static final int INK=0xff3c1d0b;
    private final HomeStyle style;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bowl=new Path(), backWave=new Path(), frontWave=new Path();
    private final Shader water;
    private final int surfaceColor;
    private State state=State.IDLE;
    private ValueAnimator motion;
    private float level;
    private long waveStarted;
    private double phase, crossPhase, speed, length, height;
    private int riseMillis;

    PowerDial(Context context,HomeStyle style) {
        super(context);
        this.style=style;
        surfaceColor=blend(style.tint,0xffffffff,.23f);
        water=new LinearGradient(0,16,0,80,blend(style.tint,0xffffffff,.08f),style.tint,Shader.TileMode.CLAMP);
        bowl.addCircle(50,50,RADIUS,Path.Direction.CW);
    }

    void show(State next) {
        if(next==state) return;
        if(motion!=null) motion.cancel();
        state=next;
        if(next==State.CONNECTING) {
            ThreadLocalRandom rng=ThreadLocalRandom.current();
            phase=rng.nextDouble(2*Math.PI);
            crossPhase=rng.nextDouble(2*Math.PI);
            speed=rng.nextDouble(2.0,3.8);
            length=rng.nextDouble(.07,.12);
            height=rng.nextDouble(2.7,4.8);
            riseMillis=rng.nextInt(2400,3700);
            waveStarted=SystemClock.uptimeMillis();
        }
        float target=next==State.CONNECTED ? 1 : next==State.CONNECTING ? MAX_PENDING : 0;
        if(!isLaidOut() && next!=State.CONNECTING) { level=target; invalidate(); return; }
        if(next==State.CONNECTING && level>=target) level=0; // a new attempt overtook a drain
        motion=ValueAnimator.ofFloat(level,target);
        motion.setDuration(next==State.CONNECTING?riseMillis:next==State.CONNECTED?620:720);
        motion.setInterpolator(new DecelerateInterpolator(next==State.CONNECTING?1.25f:1f));
        motion.addUpdateListener(a->{ level=(float)a.getAnimatedValue(); invalidate(); });
        motion.start();
    }

    @Override protected void onDetachedFromWindow() {
        if(motion!=null) motion.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size=Math.min(getWidth(),getHeight());
        canvas.save();
        canvas.translate((getWidth()-size)/2,(getHeight()-size)/2);
        canvas.scale(size/100,size/100);

        int center=style.dark?0xff39393d:0xffc3c3ca;
        int innerRing=style.dark?0xff4d4d51:0xffdcdce2;
        int outerRing=style.dark?0xff5d5d62:0xffededf0;
        fill(blend(outerRing,style.dark?0xff51392a:0xfffff2e9,level));
        canvas.drawCircle(50,50,49,paint);
        fill(blend(innerRing,style.dark?0xff78543e:0xffffc6a1,level));
        canvas.drawCircle(50,50,43,paint);
        fill(center);
        canvas.drawCircle(50,50,RADIUS,paint);
        glyph(canvas,isEnabled()?style.muted:style.disabled);

        if(level>0) {
            canvas.save();
            canvas.clipPath(bowl);
            if(level>=1) {
                drawWater(canvas);
                glyph(canvas,INK);
            } else {
                double seconds=(SystemClock.uptimeMillis()-waveStarted)/1000.0;
                float surface=96-92*level;
                float strength=Math.min(1,Math.min(level,1-level)*8);
                wave(backWave,surface-1.4f*strength,strength,seconds,true);
                wave(frontWave,surface+1.4f*strength,strength,seconds,false);
                fill(surfaceColor);
                canvas.drawPath(backWave,paint);
                canvas.save();
                canvas.clipPath(frontWave);
                drawWater(canvas);
                canvas.restore();
                canvas.clipPath(backWave);
                glyph(canvas,INK);
            }
            canvas.restore();
        }
        stroke(blend(center,style.tint,level),1.25f);
        canvas.drawCircle(50,50,RADIUS,paint);
        canvas.restore();
        if(isShown() && ValueAnimator.areAnimatorsEnabled() &&
                (state==State.CONNECTING || (motion!=null && motion.isRunning()))) postInvalidateOnAnimation();
    }

    private void wave(Path path,float surface,float strength,double time,boolean back) {
        path.rewind();
        for(int x=10;x<=90;x+=2) {
            double a=length*x*(back?1.25:1)+(back?-speed*.8:speed)*time+(back?crossPhase:phase);
            double b=length*x*.53-speed*.63*time+(back?phase:crossPhase);
            float y=surface+(float)(height*strength*(Math.sin(a)+.40*Math.sin(b)));
            if(x==10) path.moveTo(x,y); else path.lineTo(x,y);
        }
        path.lineTo(90,90); path.lineTo(10,90); path.close();
    }

    private void drawWater(Canvas c) {
        fill(style.tint);
        paint.setShader(water); c.drawCircle(50,50,RADIUS,paint); paint.setShader(null);
    }

    private void glyph(Canvas c,int color) {
        stroke(color,2.4f);
        c.drawArc(36,36,64,64,-49,278,false,paint);
        c.drawLine(50,35,50,49,paint);
    }

    private void fill(int color) { paint.setStyle(Paint.Style.FILL); paint.setColor(color); }
    private void stroke(int color,float width) {
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }
    private static int blend(int from,int to,float amount) {
        int r=Math.round(((from>>16)&255)*(1-amount)+((to>>16)&255)*amount);
        int g=Math.round(((from>>8)&255)*(1-amount)+((to>>8)&255)*amount);
        int b=Math.round((from&255)*(1-amount)+(to&255)*amount);
        return 0xff000000|(r<<16)|(g<<8)|b;
    }
}
