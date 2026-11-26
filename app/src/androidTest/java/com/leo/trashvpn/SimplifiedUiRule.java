package com.leo.trashvpn;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;

/** Keeps Chinese-label workflow fixtures independent of the tester's phone language. */
final class SimplifiedUiRule extends OfflineStartupRule {
    private SharedPreferences prefs;
    private String original;
    @Override protected void before() {
        super.before();
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs=context.getSharedPreferences(NetworkSettings.PREFS,Context.MODE_PRIVATE);
        original=prefs.getString(AppLanguage.PREF,null);
        prefs.edit().putString(AppLanguage.PREF,AppLanguage.SIMPLIFIED.locale.toLanguageTag()).commit();
    }
    @Override protected void after() {
        prefs.edit().putString(AppLanguage.PREF,original).commit();
        super.after();
    }
}
