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
public class SelectionCacheDeviceTest {
    @Test public void masqueCacheRoundTripsANewHostAndRejectsOutOfScopePairs() throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences fixture=base.getSharedPreferences("qa-masque-cache",Context.MODE_PRIVATE);
        Context ctx=new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) { return fixture; }
        };
        String legal="162.159.199.180:4500", invalid="162.159.200.180:4500", wrongPort="162.159.199.180:2408";
        long now=System.currentTimeMillis();
        NodeSelection.Score measured=NodeSelection.Score.measured(legal,new long[]{180,185,190,200},now);
        SelectionCache cache=new SelectionCache(ctx,WarpProfile.Protocol.MASQUE,ScanIntensity.MEDIUM);
        cache.save(Arrays.asList(measured,measured,NodeSelection.Score.measured(invalid,new long[]{1,1,1,1},now),
                NodeSelection.Score.measured(wrongPort,new long[]{1,1,1,1},now)));
        String key="MASQUE.device";
        JSONArray written=new JSONArray(fixture.getString(key,"[]"));
        assertEquals(1,written.length());
        JSONObject legalRow=written.getJSONObject(0);
        JSONArray withBadRow=new JSONArray().put(new JSONObject(legalRow.toString()).put("ep",invalid)).put(legalRow);
        assertTrue(fixture.edit().putString(key,withBadRow.toString()).commit());
        List<NodeSelection.Score> loaded=cache.load();
        assertEquals(1,loaded.size());
        assertEquals(legal,loaded.get(0).endpoint);
        assertArrayEquals(measured.samples(),loaded.get(0).samples());
    }

    @Test public void cacheRecomputesQualityFromSamplesAndStillLoadsLegacyRows() throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences fixture=base.getSharedPreferences("qa-selection-quality",Context.MODE_PRIVATE);
        Context ctx=new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) { return fixture; }
        };
        WarpProfile.Protocol p=WarpProfile.Protocol.WG;
        String steady=p.endpoints.get(0), volatileNode=p.endpoints.get(1), legacy=p.endpoints.get(2);
        long now=System.currentTimeMillis();
        SelectionCache cache=new SelectionCache(ctx,p,ScanIntensity.MEDIUM);
        cache.save(Arrays.asList(
                NodeSelection.Score.measured(volatileNode,new long[]{10,60,145,240},now),
                NodeSelection.Score.measured(steady,new long[]{180,185,190,200},now),
                new NodeSelection.Score(legacy,2,2,15,now)));
        JSONArray rows=new JSONArray(fixture.getString("WG.device","[]"));
        assertTrue(rows.getJSONObject(0).has("samples"));
        assertFalse(rows.getJSONObject(2).has("samples"));
        List<NodeSelection.Score> restored=cache.load();
        assertEquals(3,restored.size());
        assertEquals(steady,restored.get(0).endpoint);
        assertEquals(volatileNode,restored.get(1).endpoint);
        assertEquals(legacy,restored.get(2).endpoint);
        assertArrayEquals(new long[]{180,185,190,200},restored.get(0).samples());
        assertEquals(187.5,restored.get(0).medianMs,0);
        assertEquals(200,restored.get(0).p90Ms);
        assertEquals(5,restored.get(0).jitterMs,0);
        assertEquals(-1,restored.get(2).jitterMs,0);
    }

    @Test public void exportMergesAllLevelsAndKeepsOnlyFreshNodesOfTheSelectedProtocol() throws Exception {
        Context base=InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences fixtures=base.getSharedPreferences("qa-selection-export",Context.MODE_PRIVATE);
        fixtures.edit().clear().commit();
        Context ctx=new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) { return fixtures; }
        };
        WarpProfile.Protocol wg=WarpProfile.Protocol.WG, awg=WarpProfile.Protocol.AWG;
        String low=wg.endpoints.get(0),medium=wg.endpoints.get(1),high=wg.endpoints.get(2),expired=wg.endpoints.get(3);
        long now=System.currentTimeMillis();
        new SelectionCache(ctx,wg,ScanIntensity.LOW).save(Arrays.asList(new NodeSelection.Score(low,1,1,75,now),
                new NodeSelection.Score(expired,1,1,5,now-NodeSelection.TTL)));
        new SelectionCache(ctx,wg,ScanIntensity.MEDIUM).save(Collections.singletonList(new NodeSelection.Score(medium,2,2,200,now)));
        new SelectionCache(ctx,wg,ScanIntensity.HIGH).save(Arrays.asList(new NodeSelection.Score(high,3,3,100,now),
                new NodeSelection.Score(low,3,3,50,now)));
        new SelectionCache(ctx,awg,ScanIntensity.LOW).save(Collections.singletonList(new NodeSelection.Score(low,1,1,88,now)));
        List<NodeSelection.Score> exported=SelectionCache.loadAll(ctx,wg);
        assertEquals(3,exported.size());
        assertEquals(low,exported.get(0).endpoint);
        assertEquals(50,exported.get(0).ms);
        assertEquals(high,exported.get(1).endpoint);
        assertEquals(medium,exported.get(2).endpoint);
        assertEquals(1,SelectionCache.loadAll(ctx,awg).size());
        assertTrue(SelectionCache.loadAll(ctx,WarpProfile.Protocol.MASQUE).isEmpty());
    }

    @Test public void legacyScoresBelongToMediumOnlyAndHighScoresRemainIndependent() throws Exception {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences fixtures = base.getSharedPreferences("qa-selection-intensity", Context.MODE_PRIVATE);
        fixtures.edit().clear().commit(); // only fixture data, never user history
        Context ctx = new ContextWrapper(base) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return fixtures; }
        };
        WarpProfile.Protocol p = WarpProfile.Protocol.WG;
        String seed = p.endpoints.get(0);
        long now = System.currentTimeMillis();
        JSONArray legacy = new JSONArray().put(new JSONObject().put("ep", seed).put("ok", 1).put("n", 2)
                .put("ms", 450).put("t", now));
        fixtures.edit().putString("WG.device", legacy.toString()).commit();
        SelectionCache mid = new SelectionCache(ctx,p,ScanIntensity.MEDIUM);
        assertEquals(seed, mid.load().get(0).endpoint);
        fixtures.edit().putString("WG.import.HIGH", legacy.toString()).commit();
        SelectionCache high = new SelectionCache(ctx,p,ScanIntensity.HIGH);
        assertTrue(high.load().isEmpty());
        List<NodeSelection.Score> measured = new ArrayList<>();
        List<String> candidates = NodeSelection.candidates(p, Collections.singletonList(seed), new Random(17), ScanIntensity.HIGH);
        for (int i = 0; i < candidates.size(); i++)
            measured.add(new NodeSelection.Score(candidates.get(i), 2, 3, 10+i, now));
        high.save(measured);
        assertEquals(8, high.load().size());
        assertEquals(3, high.load().get(0).attempts);
        assertEquals(2, mid.load().get(0).attempts);
        assertTrue(new SelectionCache(ctx,p,ScanIntensity.LOW).load().isEmpty());
        assertTrue(new SelectionCache(ctx,WarpProfile.Protocol.AWG,ScanIntensity.HIGH).load().isEmpty());
    }
}
