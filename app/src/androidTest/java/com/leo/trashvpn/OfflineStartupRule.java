package com.leo.trashvpn;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.rules.ExternalResource;

/** Do not let UI fixtures initiate a real registration or scan with saved developer data. */
class OfflineStartupRule extends ExternalResource {
    private SharedPreferences selection,enrolled;
    private Long firstUse;
    private Boolean wg,masque;
    @Override protected void before() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->CacheBootstrap.suspendedForInstrumentation=true);
        selection=context.getSharedPreferences(NetworkSettings.PREFS,Context.MODE_PRIVATE);
        enrolled=context.getSharedPreferences("enrolled",Context.MODE_PRIVATE);
        firstUse=(Long)selection.getAll().get(EnrollmentConsent.KEY);
        wg=(Boolean)enrolled.getAll().get("wg");
        masque=(Boolean)enrolled.getAll().get("masque");
        selection.edit().remove(EnrollmentConsent.KEY).commit();
        enrolled.edit().remove("wg").remove("masque").commit();
    }
    @Override protected void after() {
        SharedPreferences.Editor selectionEdit=selection.edit();
        if(firstUse==null) selectionEdit.remove(EnrollmentConsent.KEY);
        else selectionEdit.putLong(EnrollmentConsent.KEY,firstUse);
        selectionEdit.commit();
        SharedPreferences.Editor enrollmentEdit=enrolled.edit();
        if(wg==null) enrollmentEdit.remove("wg"); else enrollmentEdit.putBoolean("wg",wg);
        if(masque==null) enrollmentEdit.remove("masque"); else enrollmentEdit.putBoolean("masque",masque);
        enrollmentEdit.commit();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->CacheBootstrap.suspendedForInstrumentation=false);
    }
}
