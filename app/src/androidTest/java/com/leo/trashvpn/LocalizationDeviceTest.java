package com.leo.trashvpn;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.os.Bundle;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LocalizationDeviceTest {
    @Test public void changingLanguageReopensSettingsInTheNewLayoutDirection() throws Exception {
        Instrumentation tester=InstrumentationRegistry.getInstrumentation();
        Context context=tester.getTargetContext();
        SharedPreferences prefs=context.getSharedPreferences(NetworkSettings.PREFS,Context.MODE_PRIVATE);
        String original=prefs.getString(AppLanguage.PREF,null);
        prefs.edit().putString(AppLanguage.PREF,"en").commit();
        MainActivity first=(MainActivity)tester.startActivitySync(new Intent(context,MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        CountDownLatch resumed=new CountDownLatch(1);
        MainActivity[] next={null};
        Application.ActivityLifecycleCallbacks watcher=new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                if(activity!=first && activity instanceof MainActivity main) {
                    next[0]=main; resumed.countDown();
                }
            }
            @Override public void onActivityCreated(Activity activity,Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity,Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        };
        try {
            tester.runOnMainSync(()->{
                first.getApplication().registerActivityLifecycleCallbacks(watcher);
                java.util.ArrayList<View> controls=new java.util.ArrayList<>();
                first.<View>findViewById(R.id.home_page).findViewsWithText(controls,"Settings",View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                assertFalse(controls.isEmpty());
                assertTrue(controls.get(0).performClick());
                assertEquals("Language",first.<View>findViewById(R.id.settings_language).getContentDescription());
                first.setLanguage(AppLanguage.PERSIAN);
            });
            assertTrue("Language change did not recreate settings",resumed.await(5,TimeUnit.SECONDS));
            tester.runOnMainSync(()->{
                View control=next[0].findViewById(R.id.settings_language);
                assertNotNull(control);
                assertEquals("زبان",control.getContentDescription());
                assertEquals(View.LAYOUT_DIRECTION_RTL,control.getLayoutDirection());
                assertEquals("fa",prefs.getString(AppLanguage.PREF,null));
            });
        } finally {
            tester.runOnMainSync(()->{
                first.getApplication().unregisterActivityLifecycleCallbacks(watcher);
                if(next[0]!=null) next[0].finish();
                else first.finish();
                prefs.edit().putString(AppLanguage.PREF,original).apply();
            });
        }
    }

    @Test public void resolvesUiAndRunningStatusForAllFourLanguages() {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs=app.getSharedPreferences(NetworkSettings.PREFS,Context.MODE_PRIVATE);
        String original=prefs.getString(AppLanguage.PREF,null);
        String[] settings={"设置","設定","Settings","تنظیمات"};
        String[] connected={"已连接","已連線","Connected","متصل"};
        UiText active=WarpVpnService.Status.connected(ExitLocation.UNKNOWN,null).title;
        try {
            for(AppLanguage language:AppLanguage.values()) {
                assertTrue(prefs.edit().putString(AppLanguage.PREF,language.locale.toLanguageTag()).commit());
                Context localized=AppLanguage.wrap(app);
                assertEquals(language,AppLanguage.current(localized));
                assertEquals(settings[language.ordinal()],localized.getString(R.string.settings));
                assertEquals(connected[language.ordinal()],active.get(localized));
                assertEquals(language==AppLanguage.PERSIAN?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR,
                        localized.getResources().getConfiguration().getLayoutDirection());
                assertFalse(localized.getString(R.string.intensity_description,6,48,2,1,30).isBlank());
            }
        } finally { prefs.edit().putString(AppLanguage.PREF,original).commit(); }
    }
}
