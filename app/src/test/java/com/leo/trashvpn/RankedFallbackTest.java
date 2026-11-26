package com.leo.trashvpn;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RankedFallbackTest {
    private List<NodeSelection.Score> scores(int n) {
        List<NodeSelection.Score> out=new ArrayList<>();
        for(int i=1;i<=n;i++) out.add(new NodeSelection.Score("node"+i,1,1,i,1));
        return out;
    }
    @Test public void failedLeaderIsRemovedAndSecondCandidateConnects() throws Exception {
        List<String> visited=new ArrayList<>();
        List<NodeSelection.Score> ranked=scores(3);
        assertTrue(RankedFallback.connect(ranked,(ep,i,total)->{
            visited.add(ep);
            assertEquals(3,total);
            return i==2;
        },remaining->assertEquals(ranked.subList(1,3),remaining)));
        assertEquals(Arrays.asList("node1","node2"),visited);
    }
    @Test public void allFailuresAreBoundedAtFiveAndEmptyCacheDoesNotDial() throws Exception {
        assertFalse(RankedFallback.connect(scores(8),(ep,i,total)->{
            assertEquals(5,total); assertTrue(i<=total); return false;
        },remaining->{ assertTrue(remaining.size()>=3); }));
        assertFalse(RankedFallback.connect(Collections.emptyList(),(ep,i,n)->{ throw new AssertionError(); },
                remaining->{ throw new AssertionError(); }));
    }
}
