package com.leo.trashvpn;

import org.junit.Test;
import static org.junit.Assert.*;

public class CacheBootstrapTest {
    @Test public void firstOpenClaimsUncachedProfileWithoutAUserActionAndDoesNotLoop() {
        CacheBootstrap plan=new CacheBootstrap();
        WarpProfile.Protocol p=WarpProfile.Protocol.AWG;
        ScanIntensity level=ScanIntensity.MEDIUM;
        assertTrue(plan.claim(p,level,false));
        assertFalse(plan.claim(p,level,false));
        CacheBootstrap restored=new CacheBootstrap();
        restored.restore(plan.save());
        assertFalse(restored.claim(p,level,false));
    }

    @Test public void validCacheSkipsBootstrapAndOtherUncachedProfilesRemainEligible() {
        CacheBootstrap plan=new CacheBootstrap();
        assertFalse(plan.claim(WarpProfile.Protocol.AWG,ScanIntensity.MEDIUM,true));
        assertTrue(plan.claim(WarpProfile.Protocol.AWG,ScanIntensity.MEDIUM,false));
        assertTrue(plan.claim(WarpProfile.Protocol.WG,ScanIntensity.LOW,false));
    }
}
