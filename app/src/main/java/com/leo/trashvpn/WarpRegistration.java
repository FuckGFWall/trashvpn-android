package com.leo.trashvpn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;

import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.sec.SECObjectIdentifiers;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Base64;
import javax.net.ssl.HttpsURLConnection;

/** The two device slots are independent: enrolling MASQUE must never rotate the WG/AWG key. */
final class WarpRegistration {
    static final String HOST = "https://api.cloudflareclient.com/v0a4471/reg";
    private static final SecureRandom RANDOM = new SecureRandom();
    interface Store {
        JSONObject read() throws Exception;
        void save(JSONObject o) throws Exception;
    }
    interface Api {
        JSONObject call(String method, String suffix, String bearer, JSONObject request) throws Exception;
    }

    static KeyStoreFile slot(Context c, boolean masque) {
        return new KeyStoreFile(c, masque ? "device-masque.v2" : "device-wireguard.v2");
    }

    interface Progress { void update(int step,int title); }

    static synchronized TunnelIdentity register(Context context, boolean masque, Progress progress) throws Exception {
        KeyStoreFile file = slot(context, masque);
        Network network = context.getSystemService(ConnectivityManager.class).getActiveNetwork();
        TunnelIdentity identity = provision(masque, new Store() {
            public JSONObject read() throws Exception { return file.exists() ? file.readRecord() : null; }
            public void save(JSONObject o) throws Exception { file.writeRecord(o); }
        }, (method, suffix, bearer, request) -> {
            if (network == null) throw new IOException("No active network");
            return RegistrationRoutes.send(URI.create(HOST + suffix), RegistrationRoutes::systemHttpProxy,
                    proxy -> request(network, proxy, method, suffix, bearer, request));
        }, progress);
        context.getSharedPreferences("enrolled", Context.MODE_PRIVATE).edit()
                .putBoolean(masque ? "masque" : "wg", true).apply();
        return identity;
    }

    static boolean available(Context c, boolean masque) {
        return slot(c, masque).exists() && c.getSharedPreferences("enrolled", Context.MODE_PRIVATE)
                .getBoolean(masque ? "masque" : "wg", false);
    }

    static TunnelIdentity identity(Context c,WarpProfile.Protocol protocol) throws Exception {
        boolean masque=protocol==WarpProfile.Protocol.MASQUE;
        return ready(slot(c,masque).readRecord(),masque);
    }

    static TunnelIdentity ready(JSONObject record, boolean masque) throws Exception {
        if (!"ready".equals(record.getString("state"))) throw new IOException("Pending enrollment");
        TunnelIdentity id = new TunnelIdentity(masque, record.getString("private"), record.getString("peer"),
                record.getString("ip"), record.optString("reserved"));
        byte[] peer = Base64.getDecoder().decode(id.peerKey);
        if (masque) {
            SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(peer);
            if (!SECObjectIdentifiers.secp256r1.equals(spki.getAlgorithm().getParameters()))
                throw new IOException("Expected P-256 MASQUE peer");
            KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(peer));
        } else {
            int nonzero = 0;
            for (byte b : peer) nonzero |= b;
            if (nonzero == 0) throw new IOException("Invalid WireGuard peer");
        }
        return id;
    }

    static TunnelIdentity provision(boolean masque, Store store, Api api) throws Exception {
        return provision(masque, store, api, (step, title) -> {});
    }

    static TunnelIdentity provision(boolean masque, Store store, Api api, Progress progress) throws Exception {
        JSONObject record = store.read();
        if (record != null && "ready".equals(record.optString("state"))) return ready(record, masque);
        if (record == null) {
            progress.update(1,R.string.creating_device);
            X25519PrivateKeyParameters wg = new X25519PrivateKeyParameters(RANDOM);
            byte[] serial = new byte[8];
            RANDOM.nextBytes(serial);
            JSONObject create = new JSONObject().put("key", b64(wg.generatePublicKey().getEncoded()))
                    .put("key_type", "curve25519").put("tunnel_type", "wireguard")
                    .put("install_id", "").put("fcm_token", "").put("model", "Android")
                    .put("serial_number", b64(serial)).put("os_version", "").put("locale", "en_US")
                    .put("tos", new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.now()));
            String priv = b64(wg.getEncoded()), pub = "";
            if (masque) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
                KeyPair ec = generator.generateKeyPair();
                byte[] spki = ec.getPublic().getEncoded();
                byte[] point = SubjectPublicKeyInfo.getInstance(spki).getPublicKeyData().getBytes();
                priv = b64(new org.bouncycastle.asn1.sec.ECPrivateKey(256, ((ECPrivateKey) ec.getPrivate()).getS(),
                        new DERBitString(point), SECObjectIdentifiers.secp256r1).getEncoded());
                pub = b64(spki);
            }
            JSONObject created = api.call("POST", "", null, create);
            String id = created.getString("id"), token = created.getString("token");
            if (!id.matches("[a-zA-Z0-9_-]{1,128}") || !token.matches("[!-~]{1,2048}"))
                throw new IOException("Invalid device response");
            record = new JSONObject().put("state", "pending").put("id", id).put("token", token)
                    .put("private", priv).put("public", pub);
            if (!masque) captureConfig(record, created, false);
            store.save(record); // retry a failed PATCH with the same device and the same key
        }
        if (!"pending".equals(record.getString("state"))) throw new IOException("Invalid device state");
        JSONObject patch = masque ? new JSONObject().put("key", record.getString("public"))
                .put("key_type", "secp256r1").put("tunnel_type", "masque").put("name", "TrashVPN Android")
                : new JSONObject().put("warp_enabled", true);
        progress.update(2,masque?R.string.register_masque_key:R.string.enable_warp);
        JSONObject updated = api.call("PATCH", "/" + record.getString("id"), record.getString("token"), patch);
        if (masque || updated.has("config")) captureConfig(record, updated, masque);
        // No account-management actions are exposed: discard the bearer after enrollment.
        JSONObject result = new JSONObject().put("state", "ready").put("private", record.getString("private"))
                .put("peer", record.getString("peer")).put("ip", record.getString("ip"))
                .put("reserved", record.optString("reserved"));
        progress.update(3,R.string.save_credentials);
        TunnelIdentity checked = ready(result, masque);
        store.save(result);
        return checked;
    }

    private static void captureConfig(JSONObject record, JSONObject response, boolean masque) throws Exception {
        JSONObject config = response.getJSONObject("config");
        String ip = config.getJSONObject("interface").getJSONObject("addresses").getString("v4");
        if (ip.endsWith("/32")) ip = ip.substring(0, ip.length()-3);
        String peer = config.getJSONArray("peers").getJSONObject(0).getString("public_key").trim();
        if (masque && peer.startsWith("-----BEGIN PUBLIC KEY-----")) {
            final String head = "-----BEGIN PUBLIC KEY-----", tail = "-----END PUBLIC KEY-----";
            if (!peer.endsWith(tail) || peer.length() > 1024) throw new IOException("Invalid MASQUE peer PEM");
            // The registration API returns PEM, whereas Mihomo expects the base64 of SPKI DER.
            peer = peer.substring(head.length(), peer.length()-tail.length()).replace("\r","").replace("\n","").trim();
        }
        record.put("ip", TunnelIdentity.ipv4(ip))
              .put("peer", peer)
              .put("reserved", config.optString("client_id", ""));
    }
    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    static final class StatusError extends IOException {
        final int status;
        StatusError(int status) { super("HTTP " + status); this.status = status; }
    }
    static JSONObject request(Network network, Proxy proxy, String method, String suffix, String token, JSONObject data)
            throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) network.openConnection(new URL(HOST + suffix), proxy);
        try {
            c.setRequestMethod(method);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(9000);
            c.setReadTimeout(12000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
            c.setRequestProperty("Accept","application/json");
            c.setRequestProperty("CF-Client-Version","a-6.35-4471");
            c.setRequestProperty("User-Agent","WARP for Android");
            if (token != null) c.setRequestProperty("Authorization","Bearer " + token);
            byte[] body = data.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            OutputStream stream;
            try { stream = c.getOutputStream(); }
            catch (IOException e) { throw RegistrationRoutes.beforeBody(e); }
            try (OutputStream out = stream) { out.write(body); }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new StatusError(code);
            try (InputStream in = c.getInputStream()) {
                byte[] bytes = KeyStoreFile.readBounded(in,65536);
                try { return bytes.length == 0 ? new JSONObject() : new JSONObject(new String(bytes, StandardCharsets.UTF_8)); }
                finally { java.util.Arrays.fill(bytes, (byte)0); }
            }
        } finally { c.disconnect(); }
    }
}
