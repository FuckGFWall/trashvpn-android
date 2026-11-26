package com.leo.trashvpn;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExitLocationTest {
    @Test public void keepsTheExitCountrySeparateFromTheServingDataCenter() {
        WarpProbe.Result result=WarpProbe.Result.fromTrace("warp=plus\r\ncolo=LAX\r\nloc=JP\r\n",19);
        assertEquals(TraceResult.PLUS,result.marker);
        assertEquals(19,result.elapsedMs);
        assertEquals("JP",result.location.countryCode);
        assertEquals("🇯🇵",result.location.flag());
        assertEquals("LAX",result.location.dataCenter);
    }

    @Test public void missingOrAmbiguousMetadataDoesNotFailAVerifiedConnection() {
        WarpProbe.Result first=WarpProbe.Result.fromTrace("warp=on\nloc=US\nloc=GB\ncolo=LAX\n",0);
        assertTrue(first.marker.verified());
        assertEquals("",first.location.flag());
        assertEquals("LAX",first.location.dataCenter);
        WarpProbe.Result second=WarpProbe.Result.fromTrace("warp=on\nloc=US\ncolo=LAX-04\n",0);
        assertTrue(second.marker.verified());
        assertEquals("🇺🇸",second.location.flag());
        assertEquals("",second.location.dataCenter);
        assertEquals("",ExitLocation.parse("loc=XX").flag());
        assertEquals("",ExitLocation.parse("loc=T1").flag());
    }

    @Test public void ignoresMetadataFromUnverifiedTraces() {
        for(String prefix:new String[]{"warp=off\n","warp=on\nwarp=on\n",""}) {
            WarpProbe.Result result=WarpProbe.Result.fromTrace(prefix+"loc=US\ncolo=LAX\n",0);
            assertFalse(result.marker.verified());
            assertSame(ExitLocation.UNKNOWN,result.location);
        }
    }
}
