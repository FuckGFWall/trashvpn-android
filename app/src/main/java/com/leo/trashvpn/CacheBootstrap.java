package com.leo.trashvpn;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One unattended attempt per protocol/intensity in a foreground session, not a retry loop. */
final class CacheBootstrap {
    static boolean suspendedForInstrumentation; // Set on the main thread by offline UI fixtures only.
    private final Set<String> attempted=new HashSet<>();
    private static String key(WarpProfile.Protocol protocol,ScanIntensity level) {
        return protocol.name()+"/"+level.name();
    }
    boolean claim(WarpProfile.Protocol protocol,ScanIntensity level,boolean cached) {
        return !cached && attempted.add(key(protocol,level));
    }
    void mark(WarpProfile.Protocol protocol,ScanIntensity level) { attempted.add(key(protocol,level)); }
    ArrayList<String> save() { return new ArrayList<>(attempted); }
    void restore(List<String> keys) { if(keys!=null) attempted.addAll(keys); }
}
