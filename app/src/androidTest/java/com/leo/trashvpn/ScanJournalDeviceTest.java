package com.leo.trashvpn;

import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ScanJournalDeviceTest {
    @Test public void resumesPendingFineQueueAcrossReopenAndExpiresWithCacheTtl() throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences fixture=base.getSharedPreferences("qa-scan-journal",Context.MODE_PRIVATE);
        assertTrue(fixture.edit().clear().commit());
        Context ctx=new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) { return fixture; }
        };
        WarpProfile.Protocol p=WarpProfile.Protocol.WG;
        ScanJournal a=ScanJournal.open(ctx,p,ScanIntensity.LOW,p.endpoints);
        assertEquals(48,a.plan.size());
        a.cursor=6; a.pending.add(a.plan.get(3)); a.save();
        ScanJournal b=ScanJournal.open(ctx,p,ScanIntensity.LOW,Collections.emptyList());
        assertEquals(a.plan,b.plan); assertEquals(6,b.cursor); assertEquals(a.pending,b.pending);
        assertEquals(0,ScanJournal.open(ctx,WarpProfile.Protocol.AWG,ScanIntensity.LOW,Collections.emptyList()).cursor);
        assertEquals(0,ScanJournal.open(ctx,p,ScanIntensity.HIGH,p.endpoints).cursor);
        String key="WG.true.LOW";
        JSONObject stale=new JSONObject(fixture.getString(key,"")).put("created",System.currentTimeMillis()-NodeSelection.TTL);
        assertTrue(fixture.edit().putString(key,stale.toString()).commit());
        ScanJournal c=ScanJournal.open(ctx,p,ScanIntensity.LOW,p.endpoints);
        assertTrue(c.pending.isEmpty()); assertEquals(0,c.cursor);
        c.cursor=c.plan.size(); c.save();
        assertEquals(0,ScanJournal.open(ctx,p,ScanIntensity.LOW,p.endpoints).cursor);
    }
}
