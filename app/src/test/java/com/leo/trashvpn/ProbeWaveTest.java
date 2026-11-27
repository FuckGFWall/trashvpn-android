package com.leo.trashvpn;

import org.junit.Test;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import static org.junit.Assert.*;

public class ProbeWaveTest {
    private static final WarpProfile.Protocol H3=WarpProfile.Protocol.MASQUE;
    private final AtomicBoolean stop=new AtomicBoolean();
    private final List<Integer> timeouts=new ArrayList<>();
    private long now=1_000;

    @Test public void retriesOneTransientClosureButNotAnImmediateSuccess() throws Exception {
        ProbeWave.ProbeResult result=ProbeWave.probe(H3,7_000,20_000,stop,()->now,ms->{
            timeouts.add(ms);
            if (timeouts.size()==1) {
                SSLHandshakeException closed=new SSLHandshakeException("transport closed");
                closed.initCause(new EOFException());
                throw closed;
            }
            return 480;
        });
        assertTrue(result.retried);
        assertEquals(480,result.latencyMs);
        assertEquals(Arrays.asList(5_000,5_000),timeouts);
        timeouts.clear();
        assertFalse(ProbeWave.probe(H3,7_000,20_000,stop,()->now,ms->{timeouts.add(ms); return 480;}).retried);
        assertEquals(Collections.singletonList(5_000),timeouts);
    }

    @Test public void usesAtMostTwoAttemptsAndHonorsShorterConfiguredTimeout() throws Exception {
        ProbeWave.ProbeResult result=ProbeWave.probe(H3,2_000,20_000,stop,()->now,ms->{
            timeouts.add(ms);
            now+=ms;
            throw new SocketTimeoutException();
        });
        assertTrue(result.retried);
        assertTrue(result.latencyMs<0);
        assertEquals(Arrays.asList(2_000,2_000),timeouts);
    }

    @Test public void recognizesConscryptTransportClosureWithoutACause() throws Exception {
        for (SSLException failure:Arrays.asList(new SSLHandshakeException("Connection closed by peer"),
                new SSLException("I/O error during system call, Connection reset by peer"))) {
            timeouts.clear();
            ProbeWave.ProbeResult result=ProbeWave.probe(H3,7_000,20_000,stop,()->now,ms->{
                timeouts.add(ms);
                if (timeouts.size()==1) throw failure;
                return 550;
            });
            assertTrue(result.retried);
            assertEquals(550,result.latencyMs);
            assertEquals(Arrays.asList(5_000,5_000),timeouts);
        }
    }

    @Test public void retrySharesTheOriginalDeadlineAndKeepsACutOffWavePending() throws Exception {
        assertNull(ProbeWave.probe(H3,60_000,7_500,stop,()->now,ms->{
            timeouts.add(ms);
            now+=ms;
            throw new SocketTimeoutException();
        }));
        assertEquals(Arrays.asList(5_000,1_500),timeouts);
        assertEquals(7_500,now);
    }

    @Test public void doesNotLaunchRetryWhenTooLittleTimeRemainsOrScanIsCanceled() throws Exception {
        assertNull(ProbeWave.probe(H3,7_000,6_600,stop,()->now,ms->{
            timeouts.add(ms);
            now+=ms;
            throw new SocketTimeoutException();
        }));
        assertEquals(Collections.singletonList(5_000),timeouts);
        timeouts.clear();
        assertThrows(InterruptedException.class,()->ProbeWave.probe(H3,7_000,20_000,stop,()->now,ms->{
            timeouts.add(ms);
            stop.set(true);
            throw new EOFException();
        }));
        assertEquals(Collections.singletonList(5_000),timeouts);
    }

    @Test public void neverRetriesAPermanentErrorOrAnotherProtocol() throws Exception {
        for (WarpProfile.Protocol p:WarpProfile.Protocol.values()) {
            timeouts.clear();
            ProbeWave.ProbeResult result=ProbeWave.probe(p,7_000,20_000,stop,()->now,ms->{
                timeouts.add(ms);
                if (p!=H3) throw new SocketTimeoutException();
                SSLHandshakeException invalid=new SSLHandshakeException("untrusted certificate");
                invalid.initCause(new CertificateException());
                throw invalid;
            });
            assertFalse(result.retried);
            assertTrue(result.latencyMs<0);
            assertEquals(1,timeouts.size());
        }
        assertFalse(ProbeWave.probe(H3,7_000,20_000,stop,()->now,ms->{throw new ConnectException();}).retried);
    }

    @Test public void coarseProfileAcceptsNewV4PairsWithoutH2DirectOrTunFallback() {
        String fake=Base64.getEncoder().encodeToString(new byte[91]);
        TunnelIdentity id=new TunnelIdentity(true,fake,fake,"172.16.0.2","");
        String ep="162.159.199.84:8443";
        String yaml=ProbeWave.profile(H3,id,Collections.singletonList(ep),Collections.singletonList(37001));
        assertTrue(yaml.contains("server: 162.159.199.84"));
        assertTrue(yaml.contains("type: masque"));
        assertTrue(yaml.contains("handshake-timeout: 4"));
        assertTrue(yaml.contains("listen: 127.0.0.1"));
        assertTrue(yaml.contains("rules: ['MATCH,REJECT']"));
        assertFalse(yaml.contains("DIRECT"));
        assertFalse(yaml.contains("tun:"));
        assertFalse(yaml.contains("network: h2"));
        assertTrue(WarpProfile.render(H3,ep,id).contains("handshake-timeout: 7"));
    }
}
