package com.leo.trashvpn;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.*;

public class NodeExportTest {
    private static final String NODE1="8.39.214.93:854", NODE2="8.39.214.182:859";
    private static final TunnelIdentity WG=new TunnelIdentity(false,key(32,(byte)0xff),key(32,(byte)0xfb),
            "172.16.0.77","Af8D");
    private static final NetworkSettings SETTINGS=new NetworkSettings(6,1300,"9.9.9.9",true,false);
    private static NodeSelection.Score score(String endpoint) { return new NodeSelection.Score(endpoint,1,1,21,1); }
    private static String key(int length,byte value) {
        byte[] bytes=new byte[length];
        Arrays.fill(bytes,value);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test public void clashHasOneProxyPerCachedEndpointWithNoBuiltInExtras() throws Exception {
        String yaml=NodeExport.render(WarpProfile.Protocol.WG,
                List.of(score(NODE1),score(NODE1),score("127.0.0.1:854"),score(NODE2)),
                NodeExport.Format.CLASH,WG,SETTINGS);
        assertTrue(yaml.startsWith("proxies:\n  - name: TrashVPN-WG-01\n"));
        assertTrue(yaml.contains("  - name: TrashVPN-WG-02\n"));
        assertEquals(2,yaml.lines().filter(s->s.contains("type: wireguard")).count());
        assertTrue(yaml.contains("        reserved: [1, 255, 3]"));
        assertFalse(yaml.contains("8.39.214.178"));
        assertFalse(yaml.contains("127.0.0.1"));
    }

    @Test public void clashRetainsAwgAndMasqueSpecificFields() throws Exception {
        String awg=NodeExport.render(WarpProfile.Protocol.AWG,List.of(score("8.35.211.66:880")),
                NodeExport.Format.CLASH,WG,SETTINGS);
        assertTrue(awg.contains("amnezia-wg-option:"));
        TunnelIdentity masque=new TunnelIdentity(true,key(64,(byte)1),key(65,(byte)2),"172.16.0.88","");
        String mq=NodeExport.render(WarpProfile.Protocol.MASQUE,List.of(score("162.159.199.2:8443")),
                NodeExport.Format.CLASH,masque,SETTINGS);
        assertTrue(mq.contains("type: masque"));
        assertTrue(mq.contains("sni: consumer-masque.cloudflareclient.com"));
        assertArrayEquals(new NodeExport.Format[]{NodeExport.Format.CLASH},
                NodeExport.Format.forProtocol(WarpProfile.Protocol.MASQUE));
        assertThrows(IllegalArgumentException.class,()->NodeExport.render(WarpProfile.Protocol.AWG,
                List.of(score(NODE1)),NodeExport.Format.SING_BOX,WG,SETTINGS));
    }

    @Test public void v2rayNLinksRoundTripKeysAndConnectionParameters() throws Exception {
        String text=NodeExport.render(WarpProfile.Protocol.WG,List.of(score(NODE1),score(NODE2)),
                NodeExport.Format.V2RAYN,WG,SETTINGS);
        String[] links=text.split("\n");
        assertEquals(2,links.length);
        URI first=URI.create(links[0]);
        assertEquals("wireguard",first.getScheme());
        assertEquals("8.39.214.93",first.getHost());
        assertEquals(854,first.getPort());
        assertTrue(WG.privateKey.equals(first.getUserInfo()));
        assertTrue(first.getRawUserInfo().contains("%2F"));
        assertTrue(first.getQuery().contains("publickey="+WG.peerKey));
        assertTrue(first.getQuery().contains("address=172.16.0.77/32"));
        assertTrue(first.getQuery().contains("reserved=1,255,3"));
        assertTrue(first.getQuery().contains("mtu=1300"));
        assertTrue(first.getQuery().contains("dns=9.9.9.9"));
        assertEquals("TrashVPN-WG-01",first.getFragment());
    }

    @Test public void singBoxUsesModernEndpointsAndPerPeerReservedBytes() throws Exception {
        String text=NodeExport.render(WarpProfile.Protocol.WG,List.of(score(NODE2),score(NODE1)),
                NodeExport.Format.SING_BOX,WG,SETTINGS);
        JSONArray endpoints=new JSONObject(text).getJSONArray("endpoints");
        assertEquals(2,endpoints.length());
        JSONObject first=endpoints.getJSONObject(0);
        assertEquals("wireguard",first.getString("type"));
        assertEquals("172.16.0.77/32",first.getJSONArray("address").getString(0));
        assertTrue(WG.privateKey.equals(first.getString("private_key")));
        JSONObject peer=first.getJSONArray("peers").getJSONObject(0);
        assertEquals("8.39.214.182",peer.getString("address"));
        assertEquals(859,peer.getInt("port"));
        assertTrue(WG.peerKey.equals(peer.getString("public_key")));
        assertEquals("0.0.0.0/0",peer.getJSONArray("allowed_ips").getString(0));
        assertEquals(255,peer.getJSONArray("reserved").getInt(1));
    }
}
