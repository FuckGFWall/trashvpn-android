package com.leo.trashvpn;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ScanIntensityTest {
    @Test public void invalidOrMissingSettingDefaultsToMedium() {
        assertEquals(ScanIntensity.MEDIUM, ScanIntensity.parse(null));
        assertEquals(ScanIntensity.MEDIUM, ScanIntensity.parse("invalid"));
        for (ScanIntensity level : ScanIntensity.values()) assertSame(level, ScanIntensity.parse(level.name()));
    }

    @Test public void tierCapsAndGoalsApplyToEveryProtocol() {
        int[][] expected = {{6,2,1,30},{12,4,2,65},{24,8,3,130}};
        for (ScanIntensity l : ScanIntensity.values()) {
            assertArrayEquals(expected[l.ordinal()], new int[]{l.candidates, l.goal, l.samples, l.seconds});
            for (WarpProfile.Protocol p : WarpProfile.Protocol.values()) for (int seed = 0; seed < 40; seed++) {
                List<String> endpoints = NodeSelection.candidates(p, p.endpoints, new Random(seed), l);
                assertEquals(l.candidates, endpoints.size());
                assertEquals(endpoints.size(), new HashSet<>(endpoints).size());
                for (String ep : endpoints) assertTrue(NodeSelection.allowed(p,ep));
                assertEquals(p.endpoints.get(0), endpoints.get(0));
            }
        }
    }

    @Test public void threeSamplesAreRankedBySuccessFractionNotCount() {
        NodeSelection.Score good = new NodeSelection.Score("full",1,1,450,1);
        NodeSelection.Score twoOfThree = new NodeSelection.Score("some",2,3,10,1);
        NodeSelection.Score half = new NodeSelection.Score("half",1,2,5,1);
        assertTrue(good.compareTo(twoOfThree) < 0);
        assertTrue(twoOfThree.compareTo(half) < 0);
        assertThrows(IllegalArgumentException.class, () -> new NodeSelection.Score("bad",3,4,50,1));
    }
}
