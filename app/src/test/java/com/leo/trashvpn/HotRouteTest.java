package com.leo.trashvpn;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class HotRouteTest {
    @Test public void selectorRetainsInitialAndEachTraceUsesAnIndependentRoute() {
        for(WarpProfile.Protocol p:WarpProfile.Protocol.values()) {
            String first=p.endpoints.get(1), second=p.endpoints.get(0);
            String key=Base64.getEncoder().encodeToString(new byte[p==WarpProfile.Protocol.MASQUE?91:32]);
            TunnelIdentity id=new TunnelIdentity(p==WarpProfile.Protocol.MASQUE,key,key,"172.16.0.2","");
            HotRoute plan=new HotRoute(Arrays.asList(first,second),Arrays.asList(18443,18444));
            String yaml=plan.profile(p,id,NetworkSettings.defaults(),true);
            assertEquals("warp-0",plan.nodes.get(first).name);
            assertEquals("warp-1",plan.nodes.get(second).name);
            assertTrue(yaml.contains("type: select\n    proxies: [warp-0, warp-1]"));
            assertTrue(yaml.contains("MATCH,PHASE1-WARP"));
            assertTrue(yaml.contains("listen: 127.0.0.1\n    port: 18444\n    network: [tcp]\n"
                    +"    target: www.cloudflare.com:443\n    proxy: warp-1"));
            assertTrue(yaml.contains("proxies:\n  - name: warp-0\n"));
            assertFalse(yaml.contains("DIRECT"));
            assertTrue(plan.profile(p,id,NetworkSettings.defaults(),false).contains("MATCH,REJECT"));
        }
    }
    @Test public void allDisplayedNodesAreProbeTargetsAndOnlineRoutesCoverThem() {
        WarpProfile.Protocol p=WarpProfile.Protocol.WG;
        String selected=p.endpoints.get(0);
        String cached="8.39.214.222:854";
        String pinned="8.39.214.223:854";
        List<NodeSelection.Score> scores=Arrays.asList(new NodeSelection.Score(selected,1,1,2,3),
                new NodeSelection.Score(cached,1,1,4,5),new NodeSelection.Score("127.0.0.1:443",1,1,2,3));
        assertEquals(Arrays.asList(selected,cached),HotRoute.endpoints(p,null,scores,false)); // automatic connection still uses cache
        assertEquals(p.endpoints,HotRoute.visibleEndpoints(p,null,Collections.emptyList()));
        List<String> probe=HotRoute.visibleEndpoints(p,pinned,scores);
        assertEquals(Arrays.asList(p.endpoints.get(0),p.endpoints.get(1),p.endpoints.get(2),p.endpoints.get(3),cached,pinned),probe);
        List<String> online=HotRoute.sessionEndpoints(p,cached,pinned,scores);
        assertEquals(cached,online.get(0));
        assertEquals(new HashSet<>(probe),new HashSet<>(online));
    }
}
