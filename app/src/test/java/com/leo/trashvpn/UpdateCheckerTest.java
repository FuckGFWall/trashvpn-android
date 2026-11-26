package com.leo.trashvpn;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class UpdateCheckerTest {
    @Test public void checksOnlyStrictlyNewerStableReleaseTags() throws IOException {
        assertNull(ReleaseVersion.newerTag("1.0.0","{\"tag_name\":\"v1.0.0\"}"));
        assertNull(ReleaseVersion.newerTag("1.10.0","{\"tag_name\":\"v1.9.0\"}"));
        assertNull(ReleaseVersion.newerTag("1.0.0","{\"tag_name\":\"v1.0.0+build.2\"}"));
        assertEquals("v1.10.0",ReleaseVersion.newerTag("1.9.9","{\"tag_name\":\"v1.10.0\"}"));
        assertNull(ReleaseVersion.newerTag("1.0.0","{\"tag_name\":\"v2.0.0-beta\",\"prerelease\":true}"));
        assertThrows(IOException.class,()->ReleaseVersion.newerTag("1.0.0","{\"tag_name\":\"https://bad\"}"));
        assertThrows(IOException.class,()->ReleaseVersion.newerTag("1.0.0","{\"message\":\"Not Found\"}"));
    }

    @Test public void waitsForVerifiedVpnAfterDirectFailureAndAvoidsRepeatedChecks() {
        UpdateCycle<String> cycle=new UpdateCycle<>();
        assertTrue(cycle.begin());
        assertFalse(cycle.begin());
        cycle.completed(false);
        assertEquals(UpdateCycle.Phase.WAIT_VPN,cycle.phase());
        assertNull(cycle.nextVpn());
        cycle.network("vpn-1");
        assertEquals("vpn-1",cycle.nextVpn());
        cycle.completed(false);
        assertNull(cycle.nextVpn());
        cycle.network(null);
        assertNull(cycle.nextVpn());
        cycle.network("vpn-2");
        assertEquals("vpn-2",cycle.nextVpn());
        cycle.completed(true);
        cycle.network("vpn-3");
        assertNull(cycle.nextVpn());
    }

    @Test public void directCheckWinsEvenIfVpnBecomesAvailableBeforeItFinishes() {
        UpdateCycle<String> cycle=new UpdateCycle<>();
        assertTrue(cycle.begin());
        cycle.network("verified");
        assertNull(cycle.nextVpn());
        cycle.completed(true);
        assertNull(cycle.nextVpn());
    }
}
