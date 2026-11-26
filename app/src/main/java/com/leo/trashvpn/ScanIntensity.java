package com.leo.trashvpn;

import android.content.Context;

/** Android probes serially through one Mihomo/TUN, not a desktop worker pool. */
enum ScanIntensity {
    LOW(R.string.intensity_low, 6, 2, 1, 30, 1, 2),
    MEDIUM(R.string.intensity_medium, 12, 4, 2, 65, 2, 5),
    HIGH(R.string.intensity_high, 24, 8, 3, 130, 4, 8);

    final int label;
    final int candidates, goal, samples, seconds, historyHints, cacheSize;

    ScanIntensity(int label, int candidates, int goal, int samples, int seconds, int historyHints, int cacheSize) {
        this.label = label;
        this.candidates = candidates;
        this.goal = goal;
        this.samples = samples;
        this.seconds = seconds;
        this.historyHints = historyHints;
        this.cacheSize = cacheSize;
    }

    static ScanIntensity parse(String name) {
        for (ScanIntensity value : values()) if (value.name().equals(name)) return value;
        return MEDIUM;
    }

    int workers() { return new int[]{6,8,10}[ordinal()]; }
    int poolSize() { return new int[]{48,112,224}[ordinal()]; }
    String description(Context context) {
        return context.getString(R.string.intensity_description,workers(),poolSize(),goal,samples,seconds);
    }
}
