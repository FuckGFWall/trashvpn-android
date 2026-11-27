package com.leo.trashvpn;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.Rule;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class HomeVisualDeviceTest {
    @Rule public final OfflineStartupRule offline=new OfflineStartupRule();

    @Test public void recentTaskPublishesTrashCanBitmap() throws Exception {
        Instrumentation tester=InstrumentationRegistry.getInstrumentation();
        MainActivity a=(MainActivity)tester.startActivitySync(new Intent(tester.getTargetContext(),MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            tester.waitForIdleSync();
            assertTaskIcon(a);
            Activity diagnostics=tester.startActivitySync(new Intent(tester.getTargetContext(),ConnectionDiagnosticsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                tester.waitForIdleSync();
                assertTaskIcon(diagnostics);
            } finally { tester.runOnMainSync(diagnostics::finish); }
        } finally { tester.runOnMainSync(a::finish); }
    }

    @SuppressWarnings("deprecation") // RecentTaskInfo.id and TaskDescription.getIcon() also work on API 26.
    private static void assertTaskIcon(Activity activity) throws Exception {
        assertEquals(R.mipmap.ic_recents,activity.getApplicationInfo().icon);
        assertEquals(R.mipmap.ic_recents,activity.getPackageManager().getActivityInfo(activity.getComponentName(),0).icon);
        ActivityManager manager=activity.getSystemService(ActivityManager.class);
        ActivityManager.RecentTaskInfo task=manager.getAppTasks().stream()
                .map(ActivityManager.AppTask::getTaskInfo)
                .filter(info->info.id==activity.getTaskId())
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(activity.getString(R.string.app_name),task.taskDescription.getLabel());
        Bitmap icon=task.taskDescription.getIcon();
        assertNotNull(icon);
        Bitmap art=android.graphics.BitmapFactory.decodeResource(activity.getResources(),R.mipmap.ic_recents);
        assertTrue(art.sameAs(icon));
    }

    @Test public void dialFillsFromBelowDrainsOnFailureAndFillsCompletelyOnlyOnSuccess() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled());
        Instrumentation tester=InstrumentationRegistry.getInstrumentation();
        MainActivity a=(MainActivity)tester.startActivitySync(new Intent(tester.getTargetContext(),MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        tester.waitForIdleSync();
        PowerDial dial=(PowerDial)find(a.findViewById(R.id.home_page),PowerDial.class);
        try {
            tester.runOnMainSync(()->{
                assertEquals(Math.round(144*dial.getResources().getDisplayMetrics().density),dial.getWidth());
                assertEquals(((ViewGroup)dial.getParent()).indexOfChild(dial)-1,
                        ((ViewGroup)dial.getParent()).indexOfChild(a.findViewById(R.id.connection_state)));
                assertFalse(warm(pixel(dial,.50f,.78f)));
                assertFalse(warm(pixel(dial,.50f,.065f))); // outer ring
                assertEquals(pixel(dial,.50f,.05f),pixel(dial,.50f,.06f));
                assertEquals(pixel(dial,.50f,.085f),pixel(dial,.50f,.10f));
                assertNotEquals(pixel(dial,.50f,.05f),pixel(dial,.50f,.10f));
                dial.show(PowerDial.State.CONNECTING);
            });
            await(tester,()->warm(pixel(dial,.50f,.78f)));
            // Even after the rise settles, the upper part of the bowl stays visibly gray.
            SystemClock.sleep(3700);
            tester.runOnMainSync(()->{
                assertFalse(warm(pixel(dial,.50f,.24f)));
                assertTrue(warm(pixel(dial,.50f,.82f)));
                dial.show(PowerDial.State.IDLE); // service did not verify the connection
            });
            await(tester,()->!warm(pixel(dial,.50f,.78f)));
            tester.runOnMainSync(()->{
                dial.show(PowerDial.State.CONNECTING);
                dial.show(PowerDial.State.CONNECTED);
            });
            await(tester,()->warm(pixel(dial,.50f,.15f)) && warm(pixel(dial,.50f,.10f)));
            tester.runOnMainSync(()->assertEquals(0xffff681b,pixel(dial,.50f,.83f)));
        } finally { tester.runOnMainSync(a::finish); }
    }

    @Test public void trackHasQuarterMilestoneAndTurnsGreenWithReadyCache() {
        Instrumentation tester=InstrumentationRegistry.getInstrumentation();
        tester.runOnMainSync(()->{
            HomeStyle style=new HomeStyle(tester.getTargetContext(),"light");
            HomeStyle.Track track=new HomeStyle.Track(tester.getTargetContext(),style);
            track.layout(0,0,400,50);
            track.update(0,false,false,HomeStyle.Track.Running.NONE);
            assertEquals(style.disabled,pixel(track,.25f,.50f));
            track.update(22,false,false,HomeStyle.Track.Running.NONE);
            assertEquals(style.tint,pixel(track,.12f,.50f));
            track.update(100,true,true,HomeStyle.Track.Running.NONE);
            assertEquals(style.ready,pixel(track,.25f,.50f));
            assertEquals(style.ready,pixel(track,.78f,.50f));
        });
    }

    @Test public void disconnectedDialGetsLighterFromTheCenterOut() {
        Instrumentation tester=InstrumentationRegistry.getInstrumentation();
        tester.runOnMainSync(()->{
            for(String theme:new String[]{"light","dark"}) {
                HomeStyle style=new HomeStyle(tester.getTargetContext(),theme);
                PowerDial dial=new PowerDial(tester.getTargetContext(),style);
                dial.layout(0,0,320,320);
                int center=pixel(dial,.50f,.22f);
                int inner=pixel(dial,.50f,.10f);
                int outer=pixel(dial,.50f,.05f);
                assertEquals(style.dark?0xff39393d:0xffc3c3ca,center);
                assertTrue(Color.luminance(inner)>Color.luminance(center));
                assertTrue(Color.luminance(outer)>Color.luminance(inner));
            }
        });
    }

    @Test public void sweepStaysBehindThumbAndStopsWhenIdle() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled());
        Instrumentation tester=InstrumentationRegistry.getInstrumentation();
        MainActivity a=(MainActivity)tester.startActivitySync(new Intent(tester.getTargetContext(),MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            tester.waitForIdleSync();
            HomeStyle.Track track=(HomeStyle.Track)find(a.findViewById(R.id.home_page),HomeStyle.Track.class);
            tester.runOnMainSync(()->track.setLayoutDirection(View.LAYOUT_DIRECTION_LTR));
            assertSweep(tester,track,20,HomeStyle.Track.Running.REGISTER,.12f,.235f);
            // Selection also sweeps the filled section before the registration marker.
            assertSweep(tester,track,55,HomeStyle.Track.Running.SELECT,.04f,.72f);
        } finally { tester.runOnMainSync(a::finish); }
    }

    private static void assertSweep(Instrumentation tester,HomeStyle.Track track,int percent,
            HomeStyle.Track.Running running,float inside,float ahead) {
        int[] base={0,0};
        boolean registered=running==HomeStyle.Track.Running.SELECT;
        tester.runOnMainSync(()->{
            track.setVisibility(View.INVISIBLE); // Snap directly to a stable progress value.
            track.update(percent,registered,false,HomeStyle.Track.Running.NONE);
            track.setVisibility(View.VISIBLE);
            base[0]=pixel(track,inside,.5f);
            base[1]=pixel(track,ahead,.5f);
            track.update(percent,registered,false,running);
        });
        await(tester,()->Color.blue(pixel(track,inside,.5f))-Color.blue(base[0])>=95);
        long deadline=SystemClock.uptimeMillis()+1900;
        while(SystemClock.uptimeMillis()<deadline) {
            tester.runOnMainSync(()->assertEquals(base[1],pixel(track,ahead,.5f)));
            SystemClock.sleep(75);
        }
        tester.runOnMainSync(()->{
            track.update(percent,registered,false,HomeStyle.Track.Running.NONE);
            assertEquals(base[0],pixel(track,inside,.5f));
            assertEquals(base[1],pixel(track,ahead,.5f));
        });
    }


    private static void await(Instrumentation tester,java.util.function.BooleanSupplier check) {
        long deadline=SystemClock.uptimeMillis()+6500;
        boolean[] matched={false};
        do {
            tester.runOnMainSync(()->matched[0]=check.getAsBoolean());
            if(matched[0]) return;
            SystemClock.sleep(70);
        } while(SystemClock.uptimeMillis()<deadline);
        fail("Animation did not reach the expected colors");
    }
    private static boolean warm(int color) { return Color.red(color)-Color.blue(color)>32; }
    private static int pixel(View view,float x,float y) {
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        int color=bitmap.getPixel((int)(x*bitmap.getWidth()),(int)(y*bitmap.getHeight()));
        bitmap.recycle();
        return color;
    }
    private static View find(View root,Class<? extends View> type) {
        if(type.isInstance(root)) return root;
        if(root instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)root;
            for(int n=0;n<group.getChildCount();n++) {
                View result=find(group.getChildAt(n),type);
                if(result!=null) return result;
            }
        }
        return null;
    }
}
