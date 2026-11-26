package com.leo.trashvpn;

import java.util.HashSet;
import java.util.Set;

/** A direct attempt followed by bounded retries on newly verified VPN networks. */
final class UpdateCycle<N> {
    enum Phase { IDLE, DIRECT, WAIT_VPN, VPN, DONE }
    private static final int MAX_VPN_TRIES=3;
    private final Set<N> tried=new HashSet<>();
    private Phase phase=Phase.IDLE;
    private N network;
    Phase phase() { return phase; }
    void network(N verified) { network=verified; }
    boolean begin() {
        if(phase==Phase.DIRECT || phase==Phase.VPN) return false;
        tried.clear();
        phase=Phase.DIRECT;
        return true;
    }
    N nextVpn() {
        if(phase!=Phase.WAIT_VPN || network==null || tried.size()>=MAX_VPN_TRIES || !tried.add(network))
            return null;
        phase=Phase.VPN;
        return network;
    }
    void completed(boolean success) {
        if(phase==Phase.DIRECT || phase==Phase.VPN)
            phase=success?Phase.DONE:Phase.WAIT_VPN;
    }
}
