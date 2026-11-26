package com.leo.trashvpn;

import org.junit.Test;
import java.io.IOException;
import java.net.*;
import java.util.*;
import javax.net.ssl.SSLHandshakeException;
import static org.junit.Assert.*;

public class RegistrationRoutesTest {
    private final URI api = URI.create(WarpRegistration.HOST);
    private final Proxy http = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("local-proxy.invalid", 8080));

    @Test public void successfulDirectRequestNeverConsultsProxy() throws Exception {
        String result = RegistrationRoutes.send(api, uri -> { throw new AssertionError(); }, route -> {
            assertEquals(Proxy.NO_PROXY, route);
            return "direct";
        });
        assertEquals("direct", result);
    }

    @Test public void connectionFailureBeforeBodyFallsBackExactlyOnce() throws Exception {
        for (IOException error : Arrays.asList(new UnknownHostException(), new ConnectException(), new SocketTimeoutException())) {
            List<Proxy> visited = new ArrayList<>();
            String result = RegistrationRoutes.send(api, uri -> { assertEquals(api, uri); return http; }, route -> {
                visited.add(route);
                if (route.equals(Proxy.NO_PROXY)) throw RegistrationRoutes.beforeBody(error);
                return "proxy";
            });
            assertEquals(Arrays.asList(Proxy.NO_PROXY, http), visited);
            assertEquals("proxy", result);
        }
    }

    @Test public void unavailableProxyOrFailedSecondAttemptDoesNotLoop() {
        for (Proxy alternative : Arrays.asList(null, Proxy.NO_PROXY, new Proxy(Proxy.Type.SOCKS, http.address()), http)) {
            List<Proxy> visited = new ArrayList<>();
            assertThrows(RegistrationRoutes.BeforeBody.class, () -> RegistrationRoutes.send(api, uri -> alternative, route -> {
                visited.add(route);
                throw RegistrationRoutes.beforeBody(new SocketTimeoutException());
            }));
            assertEquals(alternative == http ? Arrays.asList(Proxy.NO_PROXY, http) : Collections.singletonList(Proxy.NO_PROXY), visited);
        }
    }

    @Test public void noReplayOnHttpStatusTlsOrUncertainWrite() {
        SSLHandshakeException tls = new SSLHandshakeException("fixture");
        SocketException wrapped = new SocketException();
        wrapped.initCause(tls);
        for (IOException error : Arrays.asList(RegistrationRoutes.beforeBody(tls), RegistrationRoutes.beforeBody(wrapped),
                new WarpRegistration.StatusError(429), new WarpRegistration.StatusError(403),
                new SocketTimeoutException("waiting for response"), new IOException("write outcome unknown"))) {
            assertSame(error, assertThrows(IOException.class, () -> RegistrationRoutes.send(api,
                    uri -> { throw new AssertionError("must not try a proxy"); }, route -> { throw error; })));
        }
    }
}
