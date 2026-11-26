package com.leo.trashvpn;

import org.junit.Test;
import java.util.Base64;
import static org.junit.Assert.*;

public class NetworkSettingsTest {
    @Test public void validatesDraftsWithoutAcceptingConfigSyntax() {
        assertEquals(60,NetworkSettings.timeout(" 60 "));
        assertEquals(1,NetworkSettings.timeout("1"));
        assertThrows(IllegalArgumentException.class,()->NetworkSettings.timeout("0"));
        assertThrows(IllegalArgumentException.class,()->NetworkSettings.timeout("61"));
        assertThrows(IllegalArgumentException.class,()->NetworkSettings.timeout(""));
        assertEquals(0,NetworkSettings.mtu("0"));
        assertEquals(576,NetworkSettings.mtu("576"));
        assertEquals(9000,NetworkSettings.mtu("9000"));
        assertThrows(IllegalArgumentException.class,()->NetworkSettings.mtu("575"));
        assertThrows(IllegalArgumentException.class,()->NetworkSettings.mtu("9001"));
        assertEquals("",NetworkSettings.dns("  "));
        assertEquals("9.9.9.9",NetworkSettings.dns("9.9.9.9"));
        assertThrows(IllegalArgumentException.class,()->NetworkSettings.dns("1.1.1.1#DIRECT"));
        assertThrows(IllegalArgumentException.class,()->NetworkSettings.dns("1.1.1.1\nnameserver: DIRECT"));
    }

    @Test public void automaticMtuHasConservativeFallbackAndCustomMtuWins() {
        NetworkSettings automatic=NetworkSettings.defaults();
        assertEquals(1280,automatic.effectiveMtu(0));
        assertEquals(1280,automatic.effectiveMtu(1500));
        assertEquals(1220,automatic.effectiveMtu(1300));
        assertEquals(576,automatic.effectiveMtu(600));
        assertEquals(1400,new NetworkSettings(12,1400,"",true,false).effectiveMtu(1200));
    }

    @Test public void dnsProtectionAndAdvertisingApplyTheSamePolicy() {
        NetworkSettings defaults=NetworkSettings.defaults();
        assertEquals(WarpProfile.DNS,defaults.advertisedDns());
        assertEquals("0.0.0.0",defaults.dnsHijack());
        assertEquals("https://1.1.1.1/dns-query#PHASE1-WARP",defaults.upstream());
        NetworkSettings custom=new NetworkSettings(3,0,"9.9.9.9",true,false);
        assertTrue(WarpProfile.dnsBlock(custom).contains("'9.9.9.9#PHASE1-WARP'"));
        NetworkSettings unprotected=new NetworkSettings(3,0,"9.9.9.9",false,false);
        assertEquals("9.9.9.9",unprotected.advertisedDns());
        assertEquals("",unprotected.dnsHijack());
        assertEquals("dns:\n  enable: false\n",WarpProfile.dnsBlock(unprotected));
        NetworkSettings noDns=new NetworkSettings(3,0,"9.9.9.9",true,true);
        assertNull(noDns.advertisedDns());
        assertEquals("",noDns.dnsHijack());
        assertFalse(WarpProfile.dnsBlock(noDns).contains("9.9.9.9"));
    }

    @Test public void customDnsAndTimeoutReachMasqueOutbound() {
        String fake=Base64.getEncoder().encodeToString(new byte[91]);
        TunnelIdentity identity=new TunnelIdentity(true,fake,fake,"172.16.0.2","");
        WarpProfile.Protocol p=WarpProfile.Protocol.MASQUE;
        NetworkSettings settings=new NetworkSettings(11,0,"9.9.9.9",true,false);
        String yaml=WarpProfile.render(p,p.endpoints.get(0),identity,settings);
        assertTrue(yaml.contains("handshake-timeout: 11"));
        assertTrue(yaml.contains("    dns: ['9.9.9.9']"));
        assertTrue(yaml.contains("MATCH,PHASE1-WARP"));
    }
}
