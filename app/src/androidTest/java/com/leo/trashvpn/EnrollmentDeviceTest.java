package com.leo.trashvpn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.util.Base64;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class EnrollmentDeviceTest {
    @Test public void androidCryptoAndKeystoreSupportBothEnrollmentFlows() throws Exception {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (boolean masque : new boolean[]{false,true}) {
            // Separate QA slots; never open or change a user credential file.
            KeyStoreFile slot = new KeyStoreFile(c, masque ? "qa-mock-mq.keys" : "qa-mock-wg.keys");
            WarpRegistration.Store store = new WarpRegistration.Store() {
                boolean written;
                public JSONObject read() throws Exception { return written ? slot.readRecord() : null; }
                public void save(JSONObject data) throws Exception { slot.writeRecord(data); written=true; }
            };
            WarpRegistration.provision(masque, store, (method,suffix,token,req) -> {
                boolean mqPatch = masque && "PATCH".equals(method);
                byte[] peer = new byte[32]; peer[0]=9;
                if (mqPatch) {
                    java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("EC");
                    gen.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
                    peer = gen.generateKeyPair().getPublic().getEncoded();
                }
                JSONObject config = new JSONObject().put("interface",new JSONObject().put("addresses",
                        new JSONObject().put("v4","172.16.0.88"))).put("peers",new JSONArray().put(
                        new JSONObject().put("public_key",Base64.getEncoder().encodeToString(peer))));
                return new JSONObject().put("id","qa-fixture").put("token","qa-token").put("config",config);
            });
            TunnelIdentity loaded = WarpRegistration.ready(slot.readRecord(),masque);
            assertEquals("172.16.0.88",loaded.address);
            assertFalse(slot.readRecord().has("token"));
            assertTrue(WarpProfile.render(masque ? WarpProfile.Protocol.MASQUE : WarpProfile.Protocol.WG,
                    masque ? "162.159.199.2:8095" : "8.39.214.93:854",loaded).contains("ip: 172.16.0.88"));
        }
    }

    @Test public void platformHttpsTransportAcceptsPatchWithoutSendingARequest() throws Exception {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Network network = c.getSystemService(ConnectivityManager.class).getActiveNetwork();
        assertNotNull(network);
        HttpsURLConnection connection = (HttpsURLConnection)network.openConnection(new URL(WarpRegistration.HOST));
        try { connection.setRequestMethod("PATCH"); assertEquals("PATCH", connection.getRequestMethod()); }
        finally { connection.disconnect(); }
    }

    @Test public void androidRegistrationTransportUsesConnectAndDoesNotDiscloseBodyToProxy() throws Exception {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Network network = c.getSystemService(ConnectivityManager.class).getActiveNetwork();
        assertNotNull(network);
        // Local rejecting proxy: exercises Android's real CONNECT path without contacting Cloudflare.
        try (java.net.ServerSocket local = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            local.setSoTimeout(8000);
            java.util.concurrent.FutureTask<String> observed = new java.util.concurrent.FutureTask<>(() -> {
                try (java.net.Socket s = local.accept()) {
                    s.setSoTimeout(3000);
                    java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                            s.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                    StringBuilder headers = new StringBuilder();
                    for (String line; (line = in.readLine()) != null && !line.isEmpty(); ) headers.append(line).append('\n');
                    s.getOutputStream().write(("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    return headers.toString();
                }
            });
            new Thread(observed, "qa-local-proxy").start();
            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP, local.getLocalSocketAddress());
            try {
                WarpRegistration.request(network, proxy, "PATCH", "/qa-local", "qa-only-bearer",
                        new JSONObject().put("fixture", "qa-only-body"));
                fail("The proxy rejected CONNECT");
            } catch (java.io.IOException expected) { /* no TLS session or inner request */ }
            String headers = observed.get(9, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(headers.startsWith("CONNECT api.cloudflareclient.com:443 HTTP/1."));
            assertFalse(headers.contains("qa-only"));
            assertFalse(headers.contains("/qa-local"));
        }
    }
}
