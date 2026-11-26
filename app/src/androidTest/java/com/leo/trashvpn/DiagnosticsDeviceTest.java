package com.leo.trashvpn;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.Rule;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DiagnosticsDeviceTest {
    @Rule public final SimplifiedUiRule language=new SimplifiedUiRule();
    @Test public void aboutRowsFollowReferenceAndOpenDiagnostics() {
        Instrumentation tester=InstrumentationRegistry.getInstrumentation();
        MainActivity a=(MainActivity)tester.startActivitySync(new Intent(tester.getTargetContext(),MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Instrumentation.ActivityMonitor monitor=tester.addMonitor(ConnectionDiagnosticsActivity.class.getName(),null,false);
        Activity diagnostics=null;
        try {
            tester.runOnMainSync(()->{
                ViewGroup home=a.findViewById(R.id.home_page);
                assertTrue(find(home,"设置").performClick());
                ViewGroup about=a.findViewById(R.id.settings_about);
                List<String> rows=new ArrayList<>();
                for(int i=0;i<about.getChildCount();i++) {
                    View row=about.getChildAt(i);
                    if(row instanceof ViewGroup) rows.add(row.getContentDescription()==null?
                            ((TextView)((ViewGroup)row).getChildAt(0)).getText().toString()
                            :row.getContentDescription().toString());
                }
                assertEquals(Arrays.asList("官网","GitHub","Telegram","YouTube","X","版本","连接诊断"),rows);
                assertNotNull(find(a.findViewById(R.id.settings_donate),"捐赠"));
                assertTrue(find(about,"连接诊断").performClick());
            });
            diagnostics=tester.waitForMonitorWithTimeout(monitor,5000);
            assertNotNull(diagnostics);
            assertNotNull(diagnostics.findViewById(R.id.diagnostics_share));
        } finally {
            Activity opened=diagnostics;
            tester.runOnMainSync(()->{
                if(opened!=null) opened.finish();
                a.finish();
            });
            tester.removeMonitor(monitor);
        }
    }

    @Test public void shareMenuCopiesAndExportsTheVisibleUtf8Snapshot() throws Exception {
        Instrumentation tester=InstrumentationRegistry.getInstrumentation();
        ConnectionDiagnosticsActivity a=(ConnectionDiagnosticsActivity)tester.startActivitySync(
                new Intent(tester.getTargetContext(),ConnectionDiagnosticsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        String sample="[优选] 扫描完成\n[连接] WARP 已验证\n";
        ClipData[] old=new ClipData[1];
        boolean[] copied={false};
        ClipboardManager clipboard=a.getSystemService(ClipboardManager.class);
        File file=File.createTempFile("diagnostic-qa-",".txt",a.getCacheDir());
        IntentFilter filter=new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);
        filter.addCategory(Intent.CATEGORY_OPENABLE);
        filter.addDataType("text/plain");
        Instrumentation.ActivityMonitor monitor=tester.addMonitor(filter,
                new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(Uri.fromFile(file))),true);
        try {
            CountDownLatch loaded=new CountDownLatch(1);
            tester.runOnMainSync(()->DiagnosticLog.read(a,text->loaded.countDown()));
            assertTrue(loaded.await(5,TimeUnit.SECONDS));
            waitForFocus(tester,a);
            tester.runOnMainSync(()->{
                old[0]=clipboard.getPrimaryClip();
                a.showLog(sample);
                Menu menu=a.shareMenu(a.findViewById(R.id.diagnostics_share)).getMenu();
                assertEquals("复制到剪贴板",menu.getItem(0).getTitle().toString());
                assertEquals("以文件导出",menu.getItem(1).getTitle().toString());
                assertTrue(menu.performIdentifierAction(menu.getItem(0).getItemId(),0));
                copied[0]=true;
                ClipData actual=clipboard.getPrimaryClip();
                assertNotNull(actual);
                assertEquals(sample,actual.getItemAt(0).getText().toString());
                assertTrue(menu.performIdentifierAction(menu.getItem(1).getItemId(),0));
            });
            long until=SystemClock.elapsedRealtime()+5000;
            while(file.length()<sample.getBytes(StandardCharsets.UTF_8).length && SystemClock.elapsedRealtime()<until)
                SystemClock.sleep(30);
            assertEquals(sample,new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        } finally {
            tester.removeMonitor(monitor);
            tester.runOnMainSync(()->{
                if(copied[0]) {
                    if(old[0]!=null) clipboard.setPrimaryClip(old[0]);
                    else if(Build.VERSION.SDK_INT>=28) clipboard.clearPrimaryClip();
                }
                a.finish();
            });
        }
    }

    private static void waitForFocus(Instrumentation tester,Activity activity) {
        // Android 10+ allows clipboard reads only from the foreground, after window focus arrives.
        long end=SystemClock.elapsedRealtime()+5000;
        boolean[] focused={false};
        while(SystemClock.elapsedRealtime()<end) {
            tester.runOnMainSync(()->focused[0]=activity.hasWindowFocus());
            if(focused[0]) return;
            SystemClock.sleep(50);
        }
        fail("Diagnostics window did not receive focus");
    }

    private static View find(View view,String label) {
        if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())) return view;
        if(view instanceof ViewGroup group) {
            for(int i=0;i<group.getChildCount();i++) {
                View match=find(group.getChildAt(i),label);
                if(match!=null) return match;
            }
        }
        return null;
    }
}
