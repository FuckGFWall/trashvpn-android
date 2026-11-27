package com.leo.trashvpn;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class MasqueCandidatesTest {
    private static final WarpProfile.Protocol H3=WarpProfile.Protocol.MASQUE;
    private static final Set<String> PORTS=new HashSet<>(Arrays.asList("443","4443","8443","8095","500","4500","1701"));

    @Test public void onlyHostAddressesInTheTwoV4SubnetsAndTheSevenPortsAreLegal() {
        for (int subnet:new int[]{198,199}) for (String port:PORTS)
            for (int host:new int[]{1,2,3,80,254})
                assertTrue(NodeSelection.allowed(H3,"162.159."+subnet+"."+host+":"+port));
        for (String invalid:Arrays.asList("162.159.197.19:443","162.159.200.19:443","162.159.198.0:443",
                "162.159.199.255:443","162.159.198.256:443","162.159.198.03:443","162.159.199.3:0443",
                "162.159.199.3:2408","162.159.199.3:8444","[2606:4700:103::2]:443",
                "127.0.0.1:443","example.com:443"))
            assertFalse(invalid,NodeSelection.allowed(H3,invalid));
        assertFalse(NodeSelection.allowed(WarpProfile.Protocol.WG,"162.159.198.88:443"));
        assertFalse(NodeSelection.allowed(WarpProfile.Protocol.AWG,"162.159.199.88:4500"));
    }

    @Test public void fullPlansAreBoundedAndInterleaveRandomHostsWithoutRepeatingPairs() {
        int[] poolSizes={48,112,224};
        int[] sampledHosts={8,16,32};
        for (ScanIntensity level:ScanIntensity.values()) for (int seed=0; seed<40; seed++) {
            List<String> plan=NodeSelection.candidates(H3,Collections.emptyList(),new Random(seed),level,Integer.MAX_VALUE);
            assertEquals(poolSizes[level.ordinal()],plan.size());
            assertEquals(plan.size(),new HashSet<>(plan).size());
            assertEquals(H3.endpoints,plan.subList(0,H3.endpoints.size()));
            assertEquals("162.159.198.2:443",plan.get(2));
            assertEquals("162.159.199.2:4443",plan.get(5)); // another known gateway pair before wider sampling
            Set<String> ports=new HashSet<>();
            Set<String> sampled=new LinkedHashSet<>();
            for (String endpoint:plan) {
                assertTrue(endpoint,NodeSelection.allowed(H3,endpoint));
                int colon=endpoint.indexOf(':');
                String ip=endpoint.substring(0,colon);
                ports.add(endpoint.substring(colon+1));
                if (!ip.endsWith(".1") && !ip.endsWith(".2")) sampled.add(ip);
            }
            assertEquals(PORTS,ports);
            assertEquals(sampledHosts[level.ordinal()],sampled.size());
            int i=0;
            for (String ip:sampled)
                assertTrue(ip,ip.startsWith((i++%2==0 ? "162.159.198." : "162.159.199.")));
        }
    }

    @Test public void newSamplesRetainLegalHistoryAheadOfTheBuiltInGateways() {
        String historical="162.159.198.237:4500";
        ScanIntensity level=ScanIntensity.MEDIUM;
        List<String> history=Arrays.asList("162.159.200.237:4500",historical,historical);
        List<String> a=NodeSelection.candidates(H3,history,new Random(5),level,level.poolSize());
        List<String> b=NodeSelection.candidates(H3,history,new Random(8),level,level.poolSize());
        assertEquals(historical,a.get(0));
        assertEquals(historical,b.get(0));
        assertEquals(H3.endpoints,a.subList(1,3));
        assertEquals(1,Collections.frequency(a,historical));
        assertNotEquals(a,b);
    }

    @Test public void validatedHistoryCanFillTheTierGoalBeforeExploration() {
        ScanIntensity level=ScanIntensity.MEDIUM;
        List<String> history=Arrays.asList("162.159.198.78:500","162.159.199.40:8443",
                "162.159.199.81:4500","162.159.198.41:8095","162.159.199.90:1701");
        List<String> plan=NodeSelection.candidates(H3,history,new Random(5),level,level.poolSize());
        assertEquals(history.subList(0,level.goal),plan.subList(0,level.goal));
        assertEquals(H3.endpoints,plan.subList(level.goal,level.goal+H3.endpoints.size()));
    }
}
