package com.leo.trashvpn;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.Rule;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class HomeWorkflowDeviceTest {
    @Rule public final SimplifiedUiRule language=new SimplifiedUiRule();
    @Test public void cachedDelayShowsSpinnerGreenAndRedAndAllowsPickingWhenConnected() {
        Instrumentation device=InstrumentationRegistry.getInstrumentation();
        MainActivity a=(MainActivity)device.startActivitySync(new Intent(device.getTargetContext(),MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.waitForIdleSync();
        device.runOnMainSync(()->{
            View root=a.getWindow().getDecorView();
            SharedPreferences prefs=a.getSharedPreferences(NetworkSettings.PREFS,0);
            WarpProfile.Protocol p=WarpProfile.Protocol.valueOf(prefs.getString("protocol","AWG"));
            ScanIntensity l=ScanIntensity.parse(prefs.getString("intensity",null));
            java.util.List<String> targets=p.endpoints.subList(0,2);
            try {
                a.onVpnStatus(WarpVpnService.Status.connected(ExitLocation.UNKNOWN,null));
                assertTrue(find(root,p.name()+"01",true).isEnabled());
                assertTrue(find(root,"延迟",false).isEnabled());
                NodeDiagnostics.Job job=new NodeDiagnostics.Job(p,l,targets,true);
                NodeDiagnostics.current=job;
                NodeDiagnostics.changed();
                assertNotNull(find(root,"正在测试连通性",true));
                assertFalse(find(root,p.name()+"01",true).isEnabled());
                job.results.put(targets.get(0),214L);
                job.results.put(targets.get(1),-1L);
                job.done=true;
                NodeDiagnostics.changed();
                assertNull(find(root,"正在测试连通性",true));
                HomeStyle style=new HomeStyle(a,prefs.getString(HomeStyle.THEME_PREF,HomeStyle.DEFAULT_THEME));
                assertEquals(style.dark?0xff60dc91:0xff187b42,
                        ((TextView)find(root,"214 ms",false)).getCurrentTextColor());
                assertEquals(style.dark?0xffff6b66:0xffbc2724,
                        ((TextView)find(root,"-1",false)).getCurrentTextColor());
                assertTrue(find(root,p.name()+"01",true).isEnabled());
            } finally { NodeDiagnostics.current=null; a.finish(); }
        });
    }

    @Test public void verifiedExitReplacesProtocolAndIpv4UntilDisconnect() {
        Instrumentation device=InstrumentationRegistry.getInstrumentation();
        MainActivity a=(MainActivity)device.startActivitySync(new Intent(device.getTargetContext(),MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.runOnMainSync(()->{
            View root=a.getWindow().getDecorView();
            try {
                assertNotNull(find(root,"IPv4",false));
                assertNull(find(root,"国家\u202F:\u202F🇺🇸",false));
                a.onVpnStatus(WarpVpnService.Status.connected(ExitLocation.parse("loc=US\ncolo=LAX"),null));
                assertNull(find(root,"IPv4",false));
                View country=find(root,"国家\u202F:\u202F🇺🇸",false);
                View center=find(root,"数据中心\u202F:\u202FLAX",false);
                assertNotNull(country);
                assertNotNull(center);
                assertEquals(HomeStyle.Symbol.Kind.GLOBE,
                        ((HomeStyle.Symbol)((ViewGroup)country.getParent()).getChildAt(0)).kind);
                assertEquals(HomeStyle.Symbol.Kind.DATACENTER,
                        ((HomeStyle.Symbol)((ViewGroup)center.getParent()).getChildAt(0)).kind);

                a.onVpnStatus(WarpVpnService.Status.connected(ExitLocation.UNKNOWN,null));
                assertNotNull(find(root,"国家\u202F:\u202F—",false));
                assertNotNull(find(root,"数据中心\u202F:\u202F—",false));
                a.onVpnStatus(new WarpVpnService.Status(WarpVpnService.Stage.IDLE,R.string.disconnected,0));
                assertNotNull(find(root,"IPv4",false));
                assertNull(find(root,"数据中心\u202F:\u202F—",false));
            } finally { a.finish(); }
        });
    }

    @Test public void autoRowShowsOnlyTheVerifiedActiveNodeNameAndUpdatesOnSwitch() {
        Instrumentation device=InstrumentationRegistry.getInstrumentation();
        MainActivity a=(MainActivity)device.startActivitySync(new Intent(device.getTargetContext(),MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.runOnMainSync(()->{
            View root=a.getWindow().getDecorView();
            SharedPreferences prefs=a.getSharedPreferences(NetworkSettings.PREFS,0);
            WarpProfile.Protocol p=WarpProfile.Protocol.valueOf(prefs.getString("protocol","AWG"));
            String pin=HotRoute.fixedKey(p);
            String original=prefs.getString(pin,null);
            try {
                prefs.edit().putString(pin,p.endpoints.get(0)).apply();
                String name=p.name()+"02";
                WarpVpnService.Status auto=WarpVpnService.Status.connected(ExitLocation.UNKNOWN,
                        new WarpVpnService.ActiveNode(p,p.endpoints.get(1),true));
                a.onVpnStatus(auto);
                View row=find(root,a.getString(R.string.active_auto_node,name),true);
                assertNotNull(row);
                assertTrue(row.isSelected());
                TextView badge=(TextView)find(row,name,false);
                View namedRow=find(root,name,true);
                assertNotNull(badge);
                assertEquals(((TextView)find(namedRow,name,false)).getTextSize(),badge.getTextSize(),.1f);
                a.onVpnStatus(auto.switchingNode());
                assertNotNull(find(root,a.getString(R.string.active_auto_node,name),true));
                a.onVpnStatus(auto.switchRejected());
                assertNotNull(find(root,a.getString(R.string.active_auto_node,name),true));

                a.onVpnStatus(WarpVpnService.Status.connected(ExitLocation.UNKNOWN,
                        new WarpVpnService.ActiveNode(p,p.endpoints.get(0),true)));
                assertNotNull(find(root,a.getString(R.string.active_auto_node,p.name()+"01"),true));
                assertNull(find(root,a.getString(R.string.active_auto_node,name),true));
                a.onVpnStatus(WarpVpnService.Status.connected(ExitLocation.UNKNOWN,
                        new WarpVpnService.ActiveNode(p,p.endpoints.get(0),false)));
                assertNotNull(find(root,"自动选择",true));
                a.onVpnStatus(new WarpVpnService.Status(WarpVpnService.Stage.IDLE,R.string.disconnected,0));
                assertNull(find(root,a.getString(R.string.active_auto_node,p.name()+"01"),true));
            } finally {
                prefs.edit().putString(pin,original).apply();
                a.finish();
            }
        });
    }

    @Test public void pickingADifferentNodeUpdatesTheSelectionWithARealClick() {
        Instrumentation device=InstrumentationRegistry.getInstrumentation();
        MainActivity a=(MainActivity)device.startActivitySync(new Intent(device.getTargetContext(),MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.runOnMainSync(()->{
            SharedPreferences prefs=a.getSharedPreferences(NetworkSettings.PREFS,0);
            WarpProfile.Protocol p=WarpProfile.Protocol.valueOf(prefs.getString("protocol","AWG"));
            String pin=HotRoute.fixedKey(p), original=prefs.getString(pin,null);
            try {
                prefs.edit().remove(pin).apply();
                a.onVpnStatus(new WarpVpnService.Status(WarpVpnService.Stage.IDLE,R.string.disconnected,0));
                View node=find(a.getWindow().getDecorView(),p.name()+"01",true);
                assertNotNull(node);
                assertTrue(node.performClick());
                assertEquals(p.endpoints.get(0),prefs.getString(pin,null));
            } finally {
                prefs.edit().putString(pin,original).apply();
                a.finish();
            }
        });
    }

    @Test public void settingsSlideBothWaysAndCanSwitchDuringSelection() {
        Instrumentation device=InstrumentationRegistry.getInstrumentation();
        MainActivity a=(MainActivity)device.startActivitySync(new Intent(device.getTargetContext(),MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.waitForIdleSync();
        device.runOnMainSync(()->{
            View root=a.getWindow().getDecorView();
            SharedPreferences prefs=a.getSharedPreferences(NetworkSettings.PREFS,0);
            String saved=prefs.getString("protocol",null);
            WarpProfile.Protocol first=WarpProfile.Protocol.valueOf(saved==null?"AWG":saved);
            WarpProfile.Protocol next=first==WarpProfile.Protocol.AWG?WarpProfile.Protocol.WG:WarpProfile.Protocol.AWG;
            ScanService.Job old=new ScanService.Job(first,ScanIntensity.parse(prefs.getString("intensity",null)));
            try {
                ScanService.current=old;
                ScanService.notifyUi();
                View gear=find(root,"设置",true);
                assertTrue(gear.performClick());
                View settings=root.findViewById(R.id.settings_page);
                View home=root.findViewById(R.id.home_page);
                assertTrue(settings.getTranslationX()>0);
                assertEquals(View.INVISIBLE,gear.getVisibility()); // A rotating copy stays above the sliding page.
                assertTrue(find(settings,"隧道协议",true).isEnabled());
                assertTrue(settings.<View>findViewById(R.id.settings_auto_connect).isEnabled());
                a.switchProtocol(next);
                assertTrue(old.cancel.get());
                assertSame(old,ScanService.current); // Wait for the old worker before starting the next.
                assertEquals(next.name(),prefs.getString("protocol",null));
                a.switchProtocol(first);
                assertSame(old,ScanService.current);
                assertEquals(first.name(),prefs.getString("protocol",null));
                assertTrue(find(settings,"返回主页",true).performClick());
                assertTrue(home.getTranslationX()<0);
                assertEquals(View.INVISIBLE,gear.getVisibility()); // The same animation runs in reverse.
                assertNotNull(find(home,"正在切换协议",false));
            } finally {
                ScanService.current=null;
                prefs.edit().putString("protocol",saved).apply();
                a.finish();
            }
        });
    }

    @Test public void busyStagesLockHomeAndCompletedScanHidesTransientDetail() {
        Instrumentation device=InstrumentationRegistry.getInstrumentation();
        MainActivity a=(MainActivity)device.startActivitySync(new Intent(device.getTargetContext(),MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        device.runOnMainSync(()->{
            View root=a.getWindow().getDecorView();
            try {
                assertNotNull(find(root,"选择节点",false));
                assertNull(find(root,"测速",false));
                assertNotNull(find(root,"延迟",false));
                EnrollmentTask reg=new EnrollmentTask(false,EnrollmentTask.Action.CONNECT,null,
                        WarpProfile.Protocol.WG,ScanIntensity.MEDIUM);
                EnrollmentTask.current=reg;
                reg.step=2; reg.title=R.string.enable_warp;
                EnrollmentTask.observer.run();
                assertNotNull(find(root,"2/3 启用 WARP",false));
                assertFalse(find(root,"连接 VPN",true).isEnabled());
                assertFalse(find(root,"自动选择",true).isEnabled());
                EnrollmentTask.current=null;

                SharedPreferences prefs=a.getSharedPreferences(NetworkSettings.PREFS,0);
                WarpProfile.Protocol selected=WarpProfile.Protocol.valueOf(prefs.getString("protocol","AWG"));
                ScanService.Job scan=new ScanService.Job(selected,ScanIntensity.parse(prefs.getString("intensity",null)));
                ScanService.current=scan;
                scan.step=3; scan.title=R.string.scan_fine; scan.detail=new UiText(R.string.scan_continue_background); scan.percent=72;
                ScanService.notifyUi();
                assertNotNull(find(root,"3/3 WARP 精筛",false));
                assertTrue(find(root,"暂停",false).isEnabled());
                assertTrue(find(root,"设置",true).isEnabled());
                assertFalse(find(root,"连接 VPN",true).isEnabled());
                assertFalse(find(root,"自动选择",true).isEnabled());
                scan.done=true; scan.title=R.string.scan_finished;
                ScanService.notifyUi();
                assertNull(find(root,"切换应用不会取消",false));
                assertTrue(find(root,"重新优选",false).isEnabled());
                assertTrue(find(root,"连接 VPN",true).isEnabled());
                assertTrue(find(root,"自动选择",true).isEnabled());
            } finally {
                EnrollmentTask.current=null; ScanService.current=null; a.finish();
            }
        });
    }
    private static View find(View v,String value,boolean desc) {
        if(v.getVisibility()!=View.VISIBLE) return null;
        CharSequence text=desc?v.getContentDescription():v instanceof TextView?((TextView)v).getText():null;
        if(value.contentEquals(text==null?"":text)) return v;
        if(v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++) {
                View match=find(g.getChildAt(i),value,desc);
                if(match!=null) return match;
            }
        }
        return null;
    }
}
