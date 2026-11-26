package com.leo.trashvpn;

import org.junit.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WarpPhaseOneTest {
    private static final TunnelIdentity WG = new TunnelIdentity(false,fake(32,1),fake(32,2),"172.16.0.2","");
    private static final TunnelIdentity MASQUE = new TunnelIdentity(true,fake(121,3),fake(91,4),"172.16.0.2","");

    private static String render(WarpProfile.Protocol protocol,int index) {
        return WarpProfile.render(protocol,protocol.endpoints.get(index),protocol==WarpProfile.Protocol.MASQUE?MASQUE:WG);
    }

    private static String fake(int size, int marker) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) marker;
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test public void candidateProfilesHaveOnlyOneWarpOutboundAndNoDirectFallback() {
        Set<String> endpoints = new HashSet<>();
        for (WarpProfile.Protocol protocol : WarpProfile.Protocol.values()) {
            for (int i = 0; i < protocol.endpoints.size(); i++) {
                String endpoint = protocol.endpoints.get(i);
                String profile = render(protocol,i);
                assertTrue(endpoints.add(endpoint));
                assertTrue(profile.contains("server: " + endpoint.split(":")[0] + "\n"));
                assertTrue(profile.contains("port: " + endpoint.split(":")[1] + "\n"));
                assertTrue(profile.contains("  - MATCH,PHASE1-WARP\n"));
                assertTrue(profile.contains("https://1.1.1.1/dns-query#PHASE1-WARP"));
                assertFalse(profile.contains("DIRECT"));
                assertFalse(profile.contains("mixed-port:"));
                assertEquals(1, profile.split("  - name: PHASE1-WARP", -1).length - 1);
            }
        }
        assertEquals(10, endpoints.size());
    }

    @Test public void onlyAwgHasAmneziaOptions() {
        String awg = render(WarpProfile.Protocol.AWG,0);
        String wg = render(WarpProfile.Protocol.WG,0);
        assertTrue(awg.contains("    type: wireguard\n"));
        assertTrue(wg.contains("    type: wireguard\n"));
        assertTrue(awg.contains("    amnezia-wg-option:\n"));
        assertTrue(awg.contains("      i1: '<r 2><b 0x"));
        assertFalse(wg.contains("amnezia-wg-option"));
        assertTrue(wg.contains("        allowed-ips: ['0.0.0.0/0']"));
    }

    @Test public void masqueUsesH3ProfileAndMasqueCredentials() {
        String profile = render(WarpProfile.Protocol.MASQUE,0);
        assertTrue(profile.contains("    type: masque\n"));
        assertTrue(profile.contains("    ip-stack:\n      mode: gvisor\n"));
        assertTrue(profile.contains("consumer-masque.cloudflareclient.com"));
        assertTrue(profile.contains(MASQUE.privateKey));
        assertFalse(profile.contains(WG.privateKey));
        assertFalse(profile.contains("amnezia-wg-option"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesYamlInjectionInAnyKey() {
        new TunnelIdentity(true,MASQUE.privateKey,"'\nrules: [MATCH,DIRECT]","172.16.0.2","");
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesWrongWireGuardKeySize() {
        new TunnelIdentity(false,fake(31,1),WG.peerKey,"172.16.0.2","");
    }

    @Test public void traceRequiresOneExactPositiveWarpMarker() {
        assertEquals(TraceResult.ON, TraceResult.parse("ip=example\nwarp=on\n"));
        assertEquals(TraceResult.PLUS, TraceResult.parse("warp=plus\r\n"));
        assertEquals(TraceResult.NOT_WARP, TraceResult.parse("warp=off\n"));
        assertEquals(TraceResult.UNRECOGNIZED, TraceResult.parse("xwarp=on"));
        assertEquals(TraceResult.UNRECOGNIZED, TraceResult.parse("warp=onward"));
        assertEquals(TraceResult.UNRECOGNIZED, TraceResult.parse("warp=off\nwarp=on"));
        assertFalse(TraceResult.NOT_WARP.verified());
        assertTrue(TraceResult.ON.verified());
        assertTrue(TraceResult.PLUS.verified());
    }
}
