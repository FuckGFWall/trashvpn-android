package com.leo.trashvpn;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class KeyStoreFile {
    private static final String ALIAS = "trashvpn.phase1.credentials.v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int MAX = 4096;
    private static final int IV_BYTES = 12;
    private final AtomicFile file;

    KeyStoreFile(Context context, String slot) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), slot));
    }
    boolean exists() { return file.getBaseFile().isFile(); }

    private static synchronized SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey saved = (SecretKey) store.getKey(ALIAS, null);
        if (saved != null || !create) return saved;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    void writeRecord(JSONObject record) throws Exception {
        byte[] clear = record.toString().getBytes(StandardCharsets.UTF_8);
        try {
            if (clear.length > MAX) throw new IOException("Record too large");
            encrypt(clear);
        } finally { Arrays.fill(clear, (byte) 0); }
    }

    private void encrypt(byte[] clear) throws Exception {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] iv = cipher.getIV();
            if (iv.length != IV_BYTES) throw new IOException("Unexpected crypto parameters");
            byte[] encrypted = cipher.doFinal(clear);
            FileOutputStream out = file.startWrite();
            try {
                out.write(iv);
                out.write(encrypted);
                file.finishWrite(out);
            } catch (IOException | RuntimeException e) {
                file.failWrite(out);
                throw e;
            }
    }

    JSONObject readRecord() throws Exception {
        byte[] clear = decrypt();
        try { return new JSONObject(new String(clear, StandardCharsets.UTF_8)); }
        finally { Arrays.fill(clear, (byte) 0); }
    }

    private byte[] decrypt() throws Exception {
        try (FileInputStream input = file.openRead()) {
            byte[] payload = readBounded(input, MAX + IV_BYTES + 16);
            if (payload.length <= IV_BYTES + 16) throw new IOException("Invalid encrypted credentials");
            SecretKey secret = key(false);
            if (secret == null) throw new IOException("Keystore key not available");
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secret, new GCMParameterSpec(128, payload, 0, IV_BYTES));
            return cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
        }
    }

    static byte[] readBounded(InputStream input) throws IOException { return readBounded(input, MAX); }

    static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        try {
            for (int n; (n = input.read(buffer)) != -1;) {
                if (out.size() + n > limit) throw new IOException("File too large");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

}
