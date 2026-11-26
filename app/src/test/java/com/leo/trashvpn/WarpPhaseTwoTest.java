package com.leo.trashvpn;

import java.util.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class WarpPhaseTwoTest {
    private static final Instant FIRST_USE=Instant.parse("2026-11-26T08:17:02.123Z");
    static final class Journal implements WarpRegistration.Store {
        String data;
        public JSONObject read() throws Exception { return data == null ? null : new JSONObject(data); }
        public void save(JSONObject obj) { data=obj.toString(); }
    }
    static JSONObject response(int size) throws Exception {
        byte[] peer = new byte[32];
        peer[0] = 9;
        if (size == 91) {
            java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("EC");
            gen.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            peer = gen.generateKeyPair().getPublic().getEncoded();
        }
        String serverKey = size == 91 ? "-----BEGIN PUBLIC KEY-----\r\n"
                + Base64.getMimeEncoder(64, new byte[]{13,10}).encodeToString(peer) + "\r\n-----END PUBLIC KEY-----\n"
                : Base64.getEncoder().encodeToString(peer);
        return new JSONObject().put("config",new JSONObject().put("peers",new JSONArray().put(
                new JSONObject().put("public_key", serverKey)))
                .put("client_id","AQID").put("interface",new JSONObject().put("addresses",
                        new JSONObject().put("v4","172.16.0.77/32"))));
    }
    static final class FakeApi implements WarpRegistration.Api {
        int creates, patches;
        String wgPublic, ecPublic;
        boolean failPatch;
        public JSONObject call(String method,String suffix,String bearer,JSONObject fields) throws Exception {
            assertFalse(fields.has("private"));
            if ("POST".equals(method)) {
                creates++;
                assertEquals("",suffix);
                assertNull(bearer);
                assertEquals(FIRST_USE.toString(),fields.getString("tos"));
                assertEquals("wireguard",fields.getString("tunnel_type"));
                wgPublic=fields.getString("key");
                return response(32).put("id","test-device").put("token","test-token");
            }
            patches++;
            assertEquals("PATCH",method);
            assertEquals("/test-device",suffix);
            assertEquals("test-token",bearer);
            if (failPatch) throw new IOException("offline");
            if (fields.has("key")) {
                assertEquals("secp256r1",fields.getString("key_type"));
                assertEquals("masque",fields.getString("tunnel_type"));
                ecPublic=fields.getString("key");
                return response(91);
            }
            assertTrue(fields.getBoolean("warp_enabled"));
            return new JSONObject();
        }
    }

    @Test public void enrolledWireguardUsesSameKeypairAndClientIdForBothModes() throws Exception {
        Journal j=new Journal(); FakeApi api=new FakeApi();
        TunnelIdentity id=WarpRegistration.provision(false,j,FIRST_USE,api);
        byte[] priv=Base64.getDecoder().decode(id.privateKey);
        assertArrayEquals(Base64.getDecoder().decode(api.wgPublic),new X25519PrivateKeyParameters(priv,0).generatePublicKey().getEncoded());
        assertFalse(j.read().has("token"));
        for (WarpProfile.Protocol p : Arrays.asList(WarpProfile.Protocol.WG,WarpProfile.Protocol.AWG)) {
            String yaml=WarpProfile.render(p,p.endpoints.get(0),id);
            assertTrue(yaml.contains("ip: 172.16.0.77"));
            assertTrue(yaml.contains("reserved: [1, 2, 3]"));
        }
        WarpRegistration.provision(false,j,FIRST_USE,api);
        assertEquals(1,api.creates);
        assertEquals(1,api.patches);
    }

    @Test public void pendingMasqueResumesWithoutMintingAnotherDeviceAndMatchesEnrolledSpki() throws Exception {
        Journal j=new Journal(); FakeApi api=new FakeApi(); api.failPatch=true;
        assertThrows(IOException.class,()-> WarpRegistration.provision(true,j,FIRST_USE,api));
        assertEquals("pending",j.read().getString("state"));
        api.failPatch=false;
        TunnelIdentity id=WarpRegistration.provision(true,j,FIRST_USE,api);
        assertEquals(1,api.creates);
        assertEquals(2,api.patches);
        ECPrivateKey key=ECPrivateKey.getInstance(Base64.getDecoder().decode(id.privateKey));
        assertArrayEquals(ECNamedCurveTable.getByName("secp256r1").getG().multiply(key.getKey()).getEncoded(false),
                SubjectPublicKeyInfo.getInstance(Base64.getDecoder().decode(api.ecPublic)).getPublicKeyData().getBytes());
        assertEquals("",id.reservedYaml());
        assertFalse(j.read().has("token"));
    }

    @Test public void malformedEnrollmentDoesNotPromotePartialKeys() throws Exception {
        Journal j=new Journal();
        assertThrows(Exception.class,()->WarpRegistration.provision(true,j,FIRST_USE,(m,s,t,p)->
                "POST".equals(m)?response(32).put("id","test").put("token","token"):response(32)));
        assertEquals("pending",j.read().getString("state"));
    }

    @Test public void newRegistrationDoesNotSendARequestWithoutFirstUse() {
        FakeApi api=new FakeApi();
        assertThrows(IOException.class,()->WarpRegistration.provision(false,new Journal(),null,api));
        assertEquals(0,api.creates);
        assertEquals(0,api.patches);
    }

    @Test public void historyIsFilteredAndProtocolPoolsAreNotInterchangeable() {
        for (WarpProfile.Protocol p : WarpProfile.Protocol.values()) {
            String hint=p.endpoints.get(1);
            List<String> found=NodeSelection.candidates(p,Arrays.asList("127.0.0.1:443",hint,hint),new Random(4),ScanIntensity.MEDIUM);
            assertEquals(hint,found.get(0));
            assertEquals(found.size(),new HashSet<>(found).size());
            assertTrue(found.containsAll(p.endpoints));
            assertTrue(found.size()>p.endpoints.size());
            for(String ep : found) assertTrue(NodeSelection.allowed(p,ep));
        }
        assertFalse(NodeSelection.allowed(WarpProfile.Protocol.WG,"162.159.198.1:443"));
        assertFalse(NodeSelection.allowed(WarpProfile.Protocol.MASQUE,"8.35.211.66:880"));
        assertFalse(NodeSelection.allowed(WarpProfile.Protocol.WG,"8.35.211.266:880"));
    }

    @Test public void scoresPreferAvailabilityThenLatencyAndExpireAtSevenDays() {
        NodeSelection.Score fastFlaky=new NodeSelection.Score("A",1,2,10,123);
        NodeSelection.Score slowStable=new NodeSelection.Score("B",2,2,800,123);
        NodeSelection.Score fastStable=new NodeSelection.Score("C",2,2,400,123);
        List<NodeSelection.Score> scores=Arrays.asList(fastFlaky,slowStable,fastStable);
        Collections.sort(scores);
        assertEquals(Arrays.asList(fastStable,slowStable,fastFlaky),scores);
        assertFalse(fastStable.fresh(122));
        assertTrue(fastStable.fresh(123+NodeSelection.TTL-1));
        assertFalse(fastStable.fresh(123+NodeSelection.TTL));
    }
}
