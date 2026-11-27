package com.leo.trashvpn;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
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
                TextView state=root.findViewById(R.id.connection_state);
                TextView progress=root.findViewById(R.id.preparation_status);
                assertEquals(a.getString(R.string.disconnected),state.getText().toString());
                a.onVpnStatus(new WarpVpnService.Status(WarpVpnService.Stage.STARTING,R.string.vpn_starting,R.string.vpn_prepare));
                assertEquals(a.getString(R.string.connecting),state.getText().toString());
                assertEquals(a.getString(R.string.vpn_starting),progress.getText().toString());
                assertEquals(1,progress.getMaxLines());
                assertNull(find(root,a.getString(R.string.vpn_prepare),false));
                a.onVpnStatus(new WarpVpnService.Status(WarpVpnService.Stage.VERIFYING,R.string.vpn_verify_connection,R.string.vpn_verify_exit));
                assertEquals(a.getString(R.string.connecting),state.getText().toString());
                assertEquals(a.getString(R.string.vpn_verify_connection),progress.getText().toString());
                assertNull(find(root,a.getString(R.string.vpn_verify_exit),false));
                assertNotNull(find(root,"IPv4",false));
                assertNull(find(root,"国家\u202F:\u202F🇺🇸",false));
                a.onVpnStatus(WarpVpnService.Status.connected(ExitLocation.parse("loc=US\ncolo=LAX"),null));
                assertEquals(a.getString(R.string.connected),state.getText().toString());
                assertEquals("",progress.getText().toString());
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
                a.onVpnStatus(new WarpVpnService.Status(WarpVpnService.Stage.STOPPING,R.string.vpn_stopping,R.string.vpn_closing));
                assertEquals("",progress.getText().toString());
                assertNull(find(root,a.getString(R.string.vpn_stopping),false));
                assertNull(find(root,a.getString(R.string.vpn_closing),false));
                assertNotNull(find(root,"IPv4",false));
                assertNull(find(root,"数据中心\u202F:\u202F—",false));
                assertNull(find(root,"延迟\u202F:\u202F—",false));
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
                WarpVpnService.ActiveNode active=new WarpVpnService.ActiveNode(p,p.endpoints.get(1),true);
                WarpVpnService.Status auto=WarpVpnService.Status.connected(ExitLocation.UNKNOWN,active,null,147);
                a.onVpnStatus(auto);
                View row=find(root,a.getString(R.string.active_auto_node,name),true);
                assertNotNull(row);
                assertTrue(row.isSelected());
                TextView badge=(TextView)find(row,name,false);
                View namedRow=find(root,name,true);
                assertNotNull(badge);
                android.util.DisplayMetrics metrics=a.getResources().getDisplayMetrics();
                int sp=android.util.TypedValue.COMPLEX_UNIT_SP;
                assertEquals(android.util.TypedValue.applyDimension(sp,14,metrics),badge.getTextSize(),.1f);
                assertEquals(badge.getTextSize(),((TextView)find(namedRow,name,false)).getTextSize(),.1f);
                assertEquals(Math.round(44*metrics.density),row.getMinimumHeight());
                assertEquals(row.getMinimumHeight(),namedRow.getMinimumHeight());
                ViewGroup.LayoutParams ring=((ViewGroup)namedRow).getChildAt(0).getLayoutParams();
                assertEquals(Math.round(23*metrics.density),ring.width);
                assertEquals(ring.width,ring.height);
                assertEquals(android.util.TypedValue.applyDimension(sp,19,metrics),
                        ((TextView)find(root,"选择节点",false)).getTextSize(),.1f);
                assertEquals(android.util.TypedValue.applyDimension(sp,13,metrics),
                        ((TextView)find(root,"延迟",false)).getTextSize(),.1f);
                assertNotNull(find(root,"延迟\u202F:\u202F147 ms",false));
                assertNotNull(find(namedRow,"147 ms",false));
                assertEquals(android.util.TypedValue.applyDimension(sp,12,metrics),
                        ((TextView)find(namedRow,"147 ms",false)).getTextSize(),.1f);
                ScanIntensity level=ScanIntensity.parse(prefs.getString("intensity",null));
                NodeDiagnostics.Job job=new NodeDiagnostics.Job(p,level,java.util.Collections.singletonList(active.endpoint()),true,active);
                job.results.put(active.endpoint(),121L);
                job.done=true;
                NodeDiagnostics.current=job;
                NodeDiagnostics.changed();
                assertNotNull(find(root,"延迟\u202F:\u202F121 ms",false));
                assertNotNull(find(namedRow,"121 ms",false));
                TextView exitDelay=root.findViewById(R.id.exit_latency);
                HomeStyle style=new HomeStyle(a,prefs.getString(HomeStyle.THEME_PREF,HomeStyle.DEFAULT_THEME));
                int labelColor=style.dark?style.text:Color.BLACK;
                assertDelayColors(exitDelay,"121 ms",((TextView)find(namedRow,"121 ms",false)).getCurrentTextColor(),labelColor);
                job.results.put(active.endpoint(),-1L);
                NodeDiagnostics.changed();
                assertEquals("延迟\u202F:\u202F-1",exitDelay.getText().toString());
                assertDelayColors(exitDelay,"-1",((TextView)find(namedRow,"-1",false)).getCurrentTextColor(),labelColor);
                a.onVpnStatus(auto.switchingNode());
                assertNotNull(find(root,a.getString(R.string.active_auto_node,name),true));
                a.onVpnStatus(auto.switchRejected());
                assertNotNull(find(root,a.getString(R.string.active_auto_node,name),true));

                a.onVpnStatus(WarpVpnService.Status.connected(ExitLocation.UNKNOWN,
                        new WarpVpnService.ActiveNode(p,p.endpoints.get(0),true),null,202));
                assertNotNull(find(root,a.getString(R.string.active_auto_node,p.name()+"01"),true));
                assertNull(find(root,a.getString(R.string.active_auto_node,name),true));
                assertNotNull(find(root,"延迟\u202F:\u202F202 ms",false));
                assertNotNull(find(find(root,p.name()+"01",true),"202 ms",false));
                a.onVpnStatus(WarpVpnService.Status.connected(ExitLocation.UNKNOWN,
                        new WarpVpnService.ActiveNode(p,p.endpoints.get(0),false)));
                assertNotNull(find(root,"自动选择",true));
                a.onVpnStatus(new WarpVpnService.Status(WarpVpnService.Stage.IDLE,R.string.disconnected,0));
                assertNull(find(root,a.getString(R.string.active_auto_node,p.name()+"01"),true));
            } finally {
                NodeDiagnostics.current=null;
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
                EnrollmentTask.observer.run();
                assertNotNull(find(root,"1/7 设备准备 · 准备注册",false));
                reg.step=WarpRegistration.Step.CONFIGURE; reg.title=R.string.enable_warp;
                EnrollmentTask.observer.run();
                assertNotNull(find(root,"2/7 配置协议 · 启用 WARP",false));
                assertFalse(find(root,"连接 VPN",true).isEnabled());
                assertFalse(find(root,"自动选择",true).isEnabled());
                reg.step=WarpRegistration.Step.SAVE; reg.title=R.string.save_credentials;
                EnrollmentTask.observer.run();
                assertNotNull(find(root,"3/7 保存凭据 · 校验并保存凭据",false));
                EnrollmentTask.current=null;

                SharedPreferences prefs=a.getSharedPreferences(NetworkSettings.PREFS,0);
                WarpProfile.Protocol selected=WarpProfile.Protocol.valueOf(prefs.getString("protocol","AWG"));
                ScanService.Job scan=new ScanService.Job(selected,ScanIntensity.parse(prefs.getString("intensity",null)));
                ScanService.current=scan;
                ScanService.notifyUi();
                assertNotNull(find(root,"4/7 准备候选 · 读取历史节点和筛选断点",false));
                UiText detail=new UiText(R.string.scan_continue_background);
                scan.progress(LocalSelection.Step.SCREEN,R.string.scan_coarse,detail,24);
                ScanService.notifyUi();
                assertNotNull(find(root,"5/7 候选初筛 · 并发初筛",false));
                scan.progress(LocalSelection.Step.VERIFY,R.string.scan_fine,detail,72);
                ScanService.notifyUi();
                assertNotNull(find(root,"6/7 节点验证 · WARP 精筛",false));
                // The next batch can restart screening without going back a major step.
                scan.progress(LocalSelection.Step.SCREEN,R.string.scan_coarse,detail,35);
                ScanService.notifyUi();
                assertNotNull(find(root,"6/7 节点验证 · 补充初筛",false));
                assertEquals(72,scan.percent);
                assertTrue(find(root,"暂停",false).isEnabled());
                assertTrue(find(root,"设置",true).isEnabled());
                assertFalse(find(root,"连接 VPN",true).isEnabled());
                assertFalse(find(root,"自动选择",true).isEnabled());
                scan.progress(LocalSelection.Step.SUMMARY,R.string.scan_update_cache,detail,99);
                ScanService.notifyUi();
                assertNotNull(find(root,"7/7 汇总结果 · 更新本地节点缓存",false));
                scan.done=true; scan.title=R.string.scan_finished;
                ScanService.notifyUi();
                assertNotNull(find(root,"7/7 汇总结果 · 筛选完成",false));
                assertNull(find(root,"切换应用不会取消",false));
                assertTrue(find(root,"重新优选",false).isEnabled());
                assertTrue(find(root,"连接 VPN",true).isEnabled());
                assertTrue(find(root,"自动选择",true).isEnabled());
            } finally {
                EnrollmentTask.current=null; ScanService.current=null; a.finish();
            }
        });
    }
    private static void assertDelayColors(TextView label,String value,int numberColor,int labelColor) {
        assertEquals(labelColor,label.getCurrentTextColor());
        Spanned text=(Spanned)label.getText();
        ForegroundColorSpan[] spans=text.getSpans(0,text.length(),ForegroundColorSpan.class);
        assertEquals(1,spans.length);
        assertEquals(numberColor,spans[0].getForegroundColor());
        assertEquals(value,text.subSequence(text.getSpanStart(spans[0]),text.getSpanEnd(spans[0])).toString());
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
