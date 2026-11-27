package com.leo.trashvpn;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SelectionQualityTest {
    private static NodeSelection.Score quality(String name,long... samples) {
        return NodeSelection.Score.measured(name,samples,1);
    }

    @Test public void failuresAffectSuccessRateButNotTheLatencyDistribution() {
        NodeSelection.Score s=quality("node",240,0,100,900,130);
        assertEquals(0.8,s.successRate(),0.001);
        assertEquals(343,s.ms);
        assertEquals(185,s.medianMs,0);
        assertEquals(900,s.p90Ms);
        assertEquals(70,s.jitterMs,0);
    }

    @Test public void prefersReliabilityThenStabilityEvenWhenFastestSampleIsLower() {
        NodeSelection.Score unreliable=quality("unreliable",25,0,25,25,25);
        NodeSelection.Score stable=quality("stable",240,245,255,260,255);
        NodeSelection.Score volatileNode=quality("volatile",20,60,110,180,205);
        List<NodeSelection.Score> ranked=new ArrayList<>(Arrays.asList(unreliable,volatileNode,stable));
        Collections.sort(ranked);
        assertEquals(Arrays.asList(stable,volatileNode,unreliable),ranked);
    }

    @Test public void tailLatencyBreaksSimilarJitterAndMeanBreaksEqualTail() {
        NodeSelection.Score spike=quality("spike",25,25,25,25,250);
        NodeSelection.Score lowerTail=quality("lower tail",100,100,100,100,150);
        assertTrue(lowerTail.compareTo(spike)<0);
        assertTrue(quality("lower mean",90,100,100,100,150).compareTo(lowerTail)<0);
    }

    @Test public void oneFastSuccessDoesNotOutrankRepeatedEvidence() {
        NodeSelection.Score onlyOne=quality("one",2);
        assertEquals(-1,onlyOne.jitterMs,0);
        assertTrue(quality("retested",380,0,395,405).compareTo(onlyOne)<0);
        assertTrue(quality("confirmed",420,450,0,430).compareTo(new NodeSelection.Score("legacy",2,3,10,1))<0);
        NodeSelection.Score legacy=new NodeSelection.Score("legacy",3,3,5,1);
        assertEquals(-1,legacy.medianMs,0);
        assertEquals(-1,legacy.p90Ms);
        assertTrue(quality("measured",600,610,605).compareTo(legacy)<0);
    }

    @Test public void rawSamplesCannotBeMutatedOrCorrupted() {
        long[] valid={1,2,3};
        NodeSelection.Score s=NodeSelection.Score.measured("node",valid,1);
        valid[0]=0;
        s.samples()[1]=0;
        assertArrayEquals(new long[]{1,2,3},s.samples());
        assertThrows(IllegalArgumentException.class,()->quality("negative",1,-1,2));
        assertThrows(IllegalArgumentException.class,()->quality("all failed",0,0,0));
        assertThrows(IllegalArgumentException.class,()->quality("too many",1,2,3,4,5,6));
    }
}
