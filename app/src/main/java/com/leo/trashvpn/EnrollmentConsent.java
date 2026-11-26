package com.leo.trashvpn;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.Instant;

final class EnrollmentConsent {
    static final String KEY="warp-consent-v1";
    private EnrollmentConsent() { }
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(NetworkSettings.PREFS,Context.MODE_PRIVATE);
    }
    // Record the actual first enrollment time, not a fabricated installation
    // time or button click. Existing installations keep the recorded value.
    static Instant enrollmentStartedAt(Context c) {
        SharedPreferences store=prefs(c);
        long at=store.getLong(KEY,0);
        if(at<=0) {
            at=System.currentTimeMillis();
            store.edit().putLong(KEY,at).apply();
        }
        return Instant.ofEpochMilli(at);
    }
}
