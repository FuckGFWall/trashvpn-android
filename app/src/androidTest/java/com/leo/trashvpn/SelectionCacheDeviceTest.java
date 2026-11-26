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
