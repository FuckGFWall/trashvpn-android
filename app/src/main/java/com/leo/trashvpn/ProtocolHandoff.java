package com.leo.trashvpn;

/** Work to resume once the former protocol's worker and tunnel have stopped. */
enum ProtocolHandoff {
    SELECT, RECONNECT;

    enum Next { READY, SCAN, CONNECT }

    Next next(boolean hasCache) {
        if(!hasCache) return Next.SCAN;
        return this==RECONNECT?Next.CONNECT:Next.READY;
    }

    static boolean connectAfterScan(boolean enabled,int found,boolean cancelled,boolean current) {
        return enabled && found>0 && !cancelled && current;
    }
}
