package com.leo.trashvpn;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProtocolHandoffTest {
    @Test public void bothHotSwitchesScanWhenDestinationCacheIsMissing() {
        assertEquals(ProtocolHandoff.Next.SCAN,ProtocolHandoff.SELECT.next(false));
        assertEquals(ProtocolHandoff.Next.SCAN,ProtocolHandoff.RECONNECT.next(false));
    }

    @Test public void switchesUseExistingCacheWithoutScanningAgain() {
        assertEquals(ProtocolHandoff.Next.READY,ProtocolHandoff.SELECT.next(true));
        assertEquals(ProtocolHandoff.Next.CONNECT,ProtocolHandoff.RECONNECT.next(true));
    }

    @Test public void noScanCompletionCanConnectWhenDisabledOrSuperseded() {
        assertFalse(ProtocolHandoff.connectAfterScan(false,2,false,true));
        assertFalse(ProtocolHandoff.connectAfterScan(true,2,true,true));
        assertFalse(ProtocolHandoff.connectAfterScan(true,2,false,false));
        assertFalse(ProtocolHandoff.connectAfterScan(true,0,false,true));
        assertTrue(ProtocolHandoff.connectAfterScan(true,2,false,true));
    }
}
