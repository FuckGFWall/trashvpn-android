package com.leo.trashvpn;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class LocalSelectionTest {
    @Test public void onlyTheHttpsResponseBodyCanVerifyWarp() throws Exception {
        assertEquals(TraceResult.ON, LocalSelection.parseReply("HTTP/1.0 200 OK\r\n\r\nwarp=on\n"));
        assertEquals(TraceResult.PLUS, LocalSelection.parseReply("HTTP/1.1 200 OK\r\n\r\nwarp=plus\n"));
        assertFalse(LocalSelection.parseReply("HTTP/1.1 200 OK\r\nwarp=on\r\n\r\nwarp=off\n").verified());
        assertThrows(IOException.class, () -> LocalSelection.parseReply("HTTP/1.0 302 Found\r\n\r\nwarp=on\n"));
    }
    @Test public void listenerIsLoopbackOnlyAndHasOneFixedDestination() {
        String profile = LocalSelection.probeProfile("rules:\n  - MATCH,REJECT\n", 34567);
        assertTrue(profile.contains("listen: 127.0.0.1\n"));
        assertTrue(profile.contains("target: www.cloudflare.com:443\n"));
        assertTrue(profile.contains("proxy: "+WarpProfile.PROXY+"\n"));
        assertFalse(profile.contains("tun:"));
        assertThrows(IllegalArgumentException.class, () -> LocalSelection.probeProfile("",0));
    }
}
