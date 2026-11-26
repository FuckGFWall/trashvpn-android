package com.leo.trashvpn;

import java.util.Base64;

/** Only tunnel parameters cross the boundary into Mihomo; never a registration token. */
final class TunnelIdentity {
    final String privateKey, peerKey, address, clientId;

    TunnelIdentity(boolean masque, String privateKey, String peerKey, String address, String clientId) {
        this.privateKey = WarpCredentials.requireBase64(privateKey, masque ? 48 : 32, masque ? 256 : 32);
        this.peerKey = WarpCredentials.requireBase64(peerKey, masque ? 65 : 32, masque ? 256 : 32);
        this.address = ipv4(address);
        this.clientId = clientId.isEmpty() || masque ? "" : WarpCredentials.requireBase64(clientId, 3, 3);
    }

    static String ipv4(String input) {
        String[] parts = input.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4");
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255)
                throw new IllegalArgumentException("Invalid IPv4");
        }
        return input;
    }

    String reservedYaml() {
        if (clientId.isEmpty()) return "";
        byte[] b = Base64.getDecoder().decode(clientId);
        return "        reserved: [" + (b[0]&255) + ", " + (b[1]&255) + ", " + (b[2]&255) + "]\n";
    }

}
