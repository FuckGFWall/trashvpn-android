package com.leo.trashvpn;

enum TraceResult {
    ON, PLUS, NOT_WARP, UNRECOGNIZED;

    static TraceResult parse(String body) {
        TraceResult result = UNRECOGNIZED;
        boolean seen = false;
        for (String line : body.split("\\r?\\n")) {
            if (!line.startsWith("warp=")) continue;
            if (seen) return UNRECOGNIZED;
            seen = true;
            result = switch (line.substring(5)) {
                case "on" -> ON;
                case "plus" -> PLUS;
                case "off" -> NOT_WARP;
                default -> UNRECOGNIZED;
            };
        }
        return result;
    }

    boolean verified() { return this == ON || this == PLUS; }
}
