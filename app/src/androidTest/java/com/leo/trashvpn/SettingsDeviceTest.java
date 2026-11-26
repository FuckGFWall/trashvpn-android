package com.leo.trashvpn;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.Rule;
import org.junit.runner.RunWith;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SettingsDeviceTest {
    @Rule public final SimplifiedUiRule language=new SimplifiedUiRule();
    @Test public void configuredProbeDeadlineInterruptsStalledTls() throws Exception {
        // A local peer accepts TCP but never answers ClientHello. No outside service is used.
        try(java.net.ServerSocket server=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) {
            long start=android.os.SystemClock.elapsedRealtime();
            assertThrows(java.io.IOException.class,()->LocalSelection.trace(server.getLocalPort(),true,600,
                    new java.util.concurrent.atomic.AtomicBoolean()));
            long elapsed=android.os.SystemClock.elapsedRealtime()-start;
            assertTrue("Didn't wait for the configured timeout: "+elapsed,elapsed>=400);
            assertTrue("Probe ignored the short timeout: "+elapsed,elapsed<3500);
        }
    }

    @Test public void controlsPersistValidatedValuesAndReflectDnsOverride() {
        Instrumentation tester=InstrumentationRegistry.getInstrumentation();
        Context context=tester.getTargetContext();
        SharedPreferences prefs=context.getSharedPreferences(NetworkSettings.PREFS,Context.MODE_PRIVATE);
        Map<String,?> original=prefs.getAll();
        assertTrue(prefs.edit().remove(NetworkSettings.AUTO_CONNECT).remove(HomeStyle.THEME_PREF).commit());
        MainActivity activity=(MainActivity)tester.startActivitySync(new Intent(context,MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        tester.runOnMainSync(()->{
            try {
                find(activity.getWindow().getDecorView(),"设置",true).performClick();
                View root=activity.getWindow().getDecorView();
                assertNotNull(find(root,"连接协议",false));
                assertNotNull(find(root,"隧道协议",true));
                assertNotNull(find(root,"外观",false));
                assertNotNull(find(root,activity.getString(R.string.settings_dropdown,"跟随系统"),false));
                assertNotNull(find(root,"网络",false));
                assertNotNull(find(root,"DNS",false));
                assertNull(find(root,"管理设备凭据  ›",false));
                Switch auto=root.findViewById(R.id.settings_auto_connect);
                assertFalse(auto.isChecked());
                auto.setChecked(true);
                assertTrue(prefs.getBoolean(NetworkSettings.AUTO_CONNECT,false));
                auto.setChecked(false);
                assertFalse(prefs.getBoolean(NetworkSettings.AUTO_CONNECT,true));
                IntensitySlider slider=root.findViewById(R.id.settings_intensity);
                slider.select(ScanIntensity.HIGH);
                assertEquals("HIGH",prefs.getString("intensity",""));
                assertNotNull(find(root,ScanIntensity.HIGH.description(activity),false));

                EditText timeout=root.findViewById(R.id.settings_timeout);
                timeout.setText("14");
                timeout.onEditorAction(EditorInfo.IME_ACTION_DONE);
                assertEquals(14,NetworkSettings.load(context).probeSeconds);
                timeout.setText("61");
                timeout.onEditorAction(EditorInfo.IME_ACTION_DONE);
                assertNotNull(timeout.getError());
                assertEquals(14,NetworkSettings.load(context).probeSeconds);

                EditText mtu=root.findViewById(R.id.settings_mtu);
                mtu.setText("1200");
                mtu.onEditorAction(EditorInfo.IME_ACTION_DONE);
                assertEquals(1200,NetworkSettings.load(context).mtu);
                Switch leak=(Switch)find(root,"DNS 防泄漏",false);
                Switch noDns=(Switch)find(root,"不向隧道下发 DNS",false);
                noDns.setChecked(false);
                EditText dns=root.findViewById(R.id.settings_dns);
                assertTrue(dns.isEnabled());
                assertTrue(dns.getHint().toString().contains("1.1.1.1"));
                dns.setText("9.9.9.9");
                dns.onEditorAction(EditorInfo.IME_ACTION_DONE);
                assertEquals("9.9.9.9",NetworkSettings.load(context).resolver());
                leak.setChecked(true);
                noDns.setChecked(true);
                assertFalse(dns.isEnabled());
                assertFalse(leak.isEnabled());
                assertNull(NetworkSettings.load(context).advertisedDns());
                noDns.setChecked(false);
                assertTrue(leak.isChecked());
                assertTrue(dns.isEnabled());
            } finally {
                activity.finish();
                SharedPreferences.Editor edit=prefs.edit();
                for(String key:new String[]{"intensity",NetworkSettings.TIMEOUT,NetworkSettings.MTU,NetworkSettings.DNS,
                        NetworkSettings.PROTECT,NetworkSettings.NO_DNS,NetworkSettings.AUTO_CONNECT,HomeStyle.THEME_PREF}) {
                    Object value=original.get(key);
                    if(value instanceof String) edit.putString(key,(String)value);
                    else if(value instanceof Integer) edit.putInt(key,(Integer)value);
                    else if(value instanceof Boolean) edit.putBoolean(key,(Boolean)value);
                    else edit.remove(key);
                }
                assertTrue(edit.commit());
            }
        });
    }
    @Test public void strengthThumbTracksContinuousTouchAndSavesOnlyOnRelease() {
        Instrumentation tester=InstrumentationRegistry.getInstrumentation();
        Context context=tester.getTargetContext();
        SharedPreferences prefs=context.getSharedPreferences(NetworkSettings.PREFS,Context.MODE_PRIVATE);
        String original=prefs.getString("intensity",null);
        assertTrue(prefs.edit().putString("intensity",ScanIntensity.MEDIUM.name()).commit());
        MainActivity activity=(MainActivity)tester.startActivitySync(new Intent(context,MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            tester.waitForIdleSync();
            tester.runOnMainSync(()->find(activity.getWindow().getDecorView(),"设置",true).performClick());
            SystemClock.sleep(420);
            int[] position=new int[4];
            tester.runOnMainSync(()->{
                IntensitySlider slider=activity.findViewById(R.id.settings_intensity);
                slider.getLocationOnScreen(position);
                position[0]+=slider.getPaddingLeft();
                position[1]+=slider.getHeight()/2;
                position[2]=slider.getWidth()-slider.getPaddingLeft()-slider.getPaddingRight();
                position[3]=slider.getProgress();
                assertEquals(slider.getMax()/2,position[3]);
            });
            long down=SystemClock.uptimeMillis();
            touch(tester,down,MotionEvent.ACTION_DOWN,position,.50f);
            touch(tester,down,MotionEvent.ACTION_MOVE,position,.65f);
            tester.runOnMainSync(()->{
                IntensitySlider slider=activity.findViewById(R.id.settings_intensity);
                assertTrue(slider.getProgress()>position[3] && slider.getProgress()<slider.getMax());
                position[3]=slider.getProgress();
                assertEquals("MEDIUM",prefs.getString("intensity",null));
            });
            touch(tester,down,MotionEvent.ACTION_MOVE,position,.88f);
            tester.runOnMainSync(()->{
                IntensitySlider slider=activity.findViewById(R.id.settings_intensity);
                assertTrue(slider.getProgress()>position[3] && slider.getProgress()<slider.getMax());
                assertEquals("MEDIUM",prefs.getString("intensity",null));
            });
            touch(tester,down,MotionEvent.ACTION_UP,position,.88f);
            tester.runOnMainSync(()->{
                IntensitySlider slider=activity.findViewById(R.id.settings_intensity);
                assertEquals(slider.getMax(),slider.getProgress());
                assertEquals("HIGH",prefs.getString("intensity",null));
                assertTrue(slider.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,null));
                assertEquals("MEDIUM",prefs.getString("intensity",null));
            });
        } finally {
            tester.runOnMainSync(()->{
                activity.finish();
                prefs.edit().putString("intensity",original).apply();
            });
        }
    }
    private static void touch(Instrumentation tester,long down,int action,int[] position,float fraction) {
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,position[0]+position[2]*fraction,position[1],0);
        try { tester.sendPointerSync(event); }
        finally { event.recycle(); }
    }
    @Test public void defaultThemeUsesTheSystemConfiguration() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration light=new Configuration(context.getResources().getConfiguration());
        light.uiMode=(light.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
        Configuration dark=new Configuration(light);
        dark.uiMode=(dark.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        assertFalse(new HomeStyle(context.createConfigurationContext(light),HomeStyle.DEFAULT_THEME).dark);
        assertTrue(new HomeStyle(context.createConfigurationContext(dark),HomeStyle.DEFAULT_THEME).dark);
        assertFalse(new HomeStyle(context.createConfigurationContext(dark),"light").dark);
    }
    private static View find(View v,String name,boolean desc) {
        CharSequence value=desc?v.getContentDescription():v instanceof TextView?((TextView)v).getText():null;
        if(value!=null&&name.contentEquals(value)) return v;
        if(v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int n=0;n<g.getChildCount();n++) {
                View match=find(g.getChildAt(n),name,desc);
                if(match!=null) return match;
            }
        }
        return null;
    }
}
