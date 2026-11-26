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
