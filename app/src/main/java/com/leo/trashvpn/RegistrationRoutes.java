package com.leo.trashvpn;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.List;
import javax.net.ssl.SSLException;

/** Optional system HTTP CONNECT proxy; never replay a possibly submitted registration. */
final class RegistrationRoutes {
    interface Request<T> { T send(Proxy route) throws Exception; }
    interface Lookup { Proxy forUrl(URI target); }

    static final class BeforeBody extends IOException {
        BeforeBody(IOException cause) { super("Connection failed before sending request body", cause); }
    }

    static IOException beforeBody(IOException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SSLException || t instanceof CertificateException) return e;
        }
        return e instanceof SocketException || e instanceof UnknownHostException || e instanceof SocketTimeoutException
                ? new BeforeBody(e) : e;
    }

    static Proxy systemHttpProxy(URI target) {
        ProxySelector selector = ProxySelector.getDefault();
        if (selector == null) return null;
        List<Proxy> routes = selector.select(target); // respects the system's host exclusions/PAC
        if (routes != null) for (Proxy route : routes) if (usable(route)) return route;
        return null;
    }

    private static boolean usable(Proxy route) {
        return route != null && route.type() == Proxy.Type.HTTP && route.address() instanceof InetSocketAddress
                && ((InetSocketAddress) route.address()).getPort() > 0;
    }

    static <T> T send(URI target, Lookup lookup, Request<T> request) throws Exception {
        try {
            return request.send(Proxy.NO_PROXY);
        } catch (BeforeBody direct) {
            Proxy proxy = lookup.forUrl(target);
            if (!usable(proxy)) throw direct;
            return request.send(proxy); // exactly once, same URL, body, TLS trust and bearer
        }
    }
}
