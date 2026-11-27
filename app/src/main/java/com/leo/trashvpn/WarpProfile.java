package com.leo.trashvpn;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class WarpProfile {
    static final String PROXY = "PHASE1-WARP";
    static final String DNS = "198.18.0.2";
    static final String TUN_ADDRESS = "198.18.0.1";
    static final int PREFIX = 30;

    enum Protocol {
        AWG("8.35.211.66:880", "8.35.211.128:854", "8.35.211.113:854", "8.35.211.25:859"),
        WG("8.39.214.93:854", "8.39.214.182:854", "8.39.214.178:859", "8.39.125.29:854"),
        MASQUE("162.159.199.2:8443", "162.159.199.2:8095");

        final List<String> endpoints;

        Protocol(String... endpoints) { this.endpoints = Collections.unmodifiableList(Arrays.asList(endpoints)); }
    }

    static String render(Protocol protocol, String endpoint, TunnelIdentity identity) {
        return render(protocol,endpoint,identity,NetworkSettings.defaults());
    }

    static String render(Protocol protocol,String endpoint,TunnelIdentity identity,NetworkSettings settings) {
        String common = """
                mode: rule
                ipv6: false
                log-level: silent
                allow-lan: false
                find-process-mode: off
                geo-auto-update: false
                profile:
                  store-selected: false
                  store-fake-ip: false
                %s
                rules:
                  - MATCH,PHASE1-WARP
                proxies:
                """;
        return String.format(Locale.ROOT,common,dnsBlock(settings).trim())
                + proxyYaml(protocol,endpoint,PROXY,identity,settings);
    }

    static String proxyYaml(Protocol protocol,String endpoint,String name,TunnelIdentity identity,NetworkSettings settings) {
        return proxyYaml(protocol,endpoint,name,identity,settings,settings.probeSeconds);
    }

    static String proxyYaml(Protocol protocol,String endpoint,String name,TunnelIdentity identity,NetworkSettings settings,int handshakeSeconds) {
        if (!NodeSelection.allowed(protocol, endpoint)) throw new IllegalArgumentException("Invalid WARP endpoint");
        if (!name.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("Invalid node name");
        int separator = endpoint.indexOf(':');
        String ip = endpoint.substring(0, separator);
        String port = endpoint.substring(separator + 1);
        String common = """
                  - name: %s
                    ip: %s
                    udp: true
                    remote-dns-resolve: true
                    dns: ['%s']
                """;
        common=String.format(Locale.ROOT,common,name,identity.address,settings.outboundResolver());
        if (protocol == Protocol.MASQUE) {
            return common + String.format(Locale.ROOT, """
                    type: masque
                    server: %s
                    port: %s
                    sni: consumer-masque.cloudflareclient.com
                    private-key: '%s'
                    public-key: '%s'
                    congestion-controller: bbr
                    ip-stack:
                      mode: gvisor
                    handshake-timeout: %d
                """, ip, port, identity.privateKey, identity.peerKey, handshakeSeconds);
        }
        String wg = common + String.format(Locale.ROOT, """
                    type: wireguard
                    private-key: '%s'
                    peers:
                      - server: %s
                        port: %s
                        public-key: '%s'
                        allowed-ips: ['0.0.0.0/0']
                        persistent-keepalive: 25
                """, identity.privateKey, ip, port, identity.peerKey) + identity.reservedYaml();
        if (protocol == Protocol.WG) return wg;
        return wg + """
                    amnezia-wg-option:
                      jc: 6
                      jmin: 10
                      jmax: 50
                      s1: 0
                      s2: 0
                      h1: 1
                      h2: 2
                      h3: 3
                      h4: 4
                      i1: '<r 2><b 0x858000010001000000000669636c6f756403636f6d0000010001c00c000100010000105a00044d583737>'
                """;
    }

    static String dnsBlock(NetworkSettings settings) {
        if (!settings.protectedDns()) return "dns:\n  enable: false\n";
        return String.format(Locale.ROOT, """
                dns:
                  enable: true
                  ipv6: false
                  enhanced-mode: fake-ip
                  fake-ip-range: 198.19.0.1/16
                  default-nameserver: ['1.1.1.1']
                  proxy-server-nameserver: ['1.1.1.1']
                  nameserver: ['%s']
                """,settings.upstream());
    }
}
