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
        ScanJournal a=ScanJournal.open(ctx,p,ScanIntensity.LOW,p.endpoints,false);
        assertEquals(48,a.plan.size());
        a.cursor=6; a.pending.add(a.plan.get(3)); a.save();
        ScanJournal b=ScanJournal.open(ctx,p,ScanIntensity.LOW,Collections.emptyList(),false);
        assertEquals(a.plan,b.plan); assertEquals(6,b.cursor); assertEquals(a.pending,b.pending);
        assertEquals(0,ScanJournal.open(ctx,WarpProfile.Protocol.AWG,ScanIntensity.LOW,Collections.emptyList(),false).cursor);
        assertEquals(0,ScanJournal.open(ctx,p,ScanIntensity.HIGH,p.endpoints,false).cursor);
        String key="WG.true.LOW";
        JSONObject stale=new JSONObject(fixture.getString(key,"")).put("created",System.currentTimeMillis()-NodeSelection.TTL);
        assertTrue(fixture.edit().putString(key,stale.toString()).commit());
        ScanJournal c=ScanJournal.open(ctx,p,ScanIntensity.LOW,p.endpoints,false);
        assertTrue(c.pending.isEmpty()); assertEquals(0,c.cursor);
        c.cursor=c.plan.size(); c.save();
        assertEquals(0,ScanJournal.open(ctx,p,ScanIntensity.LOW,p.endpoints,false).cursor);
    }

    @Test public void masqueResumesExactPlanAndResamplingKeepsHistoryAtTheFront() throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences fixture=base.getSharedPreferences("qa-masque-journal",Context.MODE_PRIVATE);
        Context ctx=new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) { return fixture; }
        };
        WarpProfile.Protocol h3=WarpProfile.Protocol.MASQUE;
        ScanIntensity level=ScanIntensity.MEDIUM;
        String newHost="162.159.199.123:1701", key="MASQUE.true.MEDIUM";
        // Upgrade an old journal without a finished marker, using a newly legal host in the pending queue.
        JSONObject row=new JSONObject().put("created",System.currentTimeMillis()).put("cursor",1)
                .put("plan",new JSONArray(Arrays.asList(newHost,h3.endpoints.get(1))))
                .put("pending",new JSONArray(Collections.singletonList(newHost)));
        assertTrue(fixture.edit().putString(key,row.toString()).commit());
        assertTrue(ScanJournal.canContinue(ctx,h3,level));
        ScanJournal resumed=ScanJournal.open(ctx,h3,level,h3.endpoints,false);
        assertEquals(Arrays.asList(newHost,h3.endpoints.get(1)),resumed.plan);
        assertEquals(Collections.singletonList(newHost),resumed.pending);
        assertEquals(1,resumed.cursor);
        resumed.finish();
        assertFalse(ScanJournal.canContinue(ctx,h3,level));
        ScanJournal automatic=ScanJournal.open(ctx,h3,level,h3.endpoints,false);
        assertEquals(resumed.plan,automatic.plan);
        assertEquals(resumed.pending,automatic.pending);
        assertTrue(ScanJournal.canContinue(ctx,h3,level));

        List<String> history=Collections.singletonList(newHost);
        ScanJournal fresh=ScanJournal.open(ctx,h3,level,history,true);
        assertEquals(112,fresh.plan.size());
        assertEquals(0,fresh.cursor);
        assertTrue(fresh.pending.isEmpty());
        assertEquals(newHost,fresh.plan.get(0));
        assertEquals(fresh.plan,ScanJournal.open(ctx,h3,level,Collections.emptyList(),false).plan);
        ScanJournal another=ScanJournal.open(ctx,h3,level,history,true);
        assertNotEquals(fresh.plan,another.plan);
        assertEquals(newHost,another.plan.get(0));
    }

    @Test public void rejectsInvalidMasquePlanOrPendingQueue() throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences fixture=base.getSharedPreferences("qa-masque-invalid-journal",Context.MODE_PRIVATE);
        Context ctx=new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) { return fixture; }
        };
        WarpProfile.Protocol h3=WarpProfile.Protocol.MASQUE;
        String good="162.159.198.70:443", other="162.159.199.71:443", key="MASQUE.true.LOW";
        for (String invalid:Arrays.asList("162.159.200.70:443","162.159.198.70:2408","162.159.199.255:443",good)) {
            JSONObject row=new JSONObject().put("created",System.currentTimeMillis()).put("cursor",1)
                    .put("plan",new JSONArray(Arrays.asList(good,invalid))).put("pending",new JSONArray());
            assertTrue(fixture.edit().putString(key,row.toString()).commit());
            assertFalse(ScanJournal.canContinue(ctx,h3,ScanIntensity.LOW));
        }
        JSONObject unscanned=new JSONObject().put("created",System.currentTimeMillis()).put("cursor",1)
                .put("plan",new JSONArray(Arrays.asList(good,other)))
                .put("pending",new JSONArray(Collections.singletonList(other)));
        assertTrue(fixture.edit().putString(key,unscanned.toString()).commit());
        assertFalse(ScanJournal.canContinue(ctx,h3,ScanIntensity.LOW));
        assertEquals(0,ScanJournal.open(ctx,h3,ScanIntensity.LOW,Collections.emptyList(),false).cursor);
    }
}
