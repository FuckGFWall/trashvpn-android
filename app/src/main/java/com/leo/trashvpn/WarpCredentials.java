package com.leo.trashvpn;

import java.util.Arrays;
import java.util.Base64;

final class WarpCredentials {
    private WarpCredentials() {}

    static String requireBase64(String value, int min, int max) {
        if (value == null || value.length() > 344 || !value.matches("[a-zA-Z0-9+/]+={0,2}")) {
            throw new IllegalArgumentException("Invalid key encoding");
        }
        byte[] decoded = Base64.getDecoder().decode(value);
        try {
            if (decoded.length < min || decoded.length > max
                    || !Base64.getEncoder().encodeToString(decoded).equals(value)) {
                throw new IllegalArgumentException("Invalid key encoding");
            }
            return value;
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }
}
