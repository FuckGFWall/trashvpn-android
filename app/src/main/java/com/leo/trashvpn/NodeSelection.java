package com.leo.trashvpn;

import java.util.*;

// Small mobile scan batches. Only WARP IPv4 prefixes and protocol-specific UDP ports
// are legal targets, including when reading persisted endpoint history.
final class NodeSelection {
    static final long TTL = 7L * 24 * 3600 * 1000;
    static final int AUTO = -1, RESCAN = -2;
    private static final String[] WG = {"8.6.112","8.34.70","8.34.146","8.35.211","8.39.125","8.39.204",
            "8.39.214","8.47.69","162.159.192","162.159.195","188.114.96","188.114.97","188.114.98","188.114.99"};
    private static final int[] WG_PORT = {2408,854,880,500,1701,859,4500,864};
    private static final int[] MQ_PORT = {443,4443,8443,8095,500,4500,1701};
    private static final String[] MQ = {"162.159.198.2","162.159.198.1","162.159.199.2","162.159.199.1"};

    static boolean allowed(WarpProfile.Protocol p, String endpoint) {
        if (endpoint == null || !endpoint.matches("[0-9.]{7,15}:[1-9][0-9]{0,4}")) return false;
        String[] split = endpoint.split(":");
        String ip = split[0];
        try { TunnelIdentity.ipv4(ip); } catch (IllegalArgumentException e) { return false; }
        boolean mq = p == WarpProfile.Protocol.MASQUE;
        if (mq ? !Arrays.asList(MQ).contains(ip) : !Arrays.asList(WG).contains(ip.substring(0,ip.lastIndexOf('.')))) return false;
        for (int port : mq ? MQ_PORT : WG_PORT) if (port == Integer.parseInt(split[1])) return true;
        return false;
    }

    static List<String> candidates(WarpProfile.Protocol p, List<String> history, Random random, ScanIntensity level) {
        return candidates(p,history,random,level,level.candidates);
    }
    static List<String> candidates(WarpProfile.Protocol p,List<String> history,Random random,ScanIntensity level,int limit) {
        if (p == WarpProfile.Protocol.MASQUE) limit=Math.min(limit,MQ.length*MQ_PORT.length);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String ep : history) if (allowed(p, ep) && out.size() < level.historyHints) out.add(ep);
        boolean mq = p == WarpProfile.Protocol.MASQUE;
        List<String> pools = new ArrayList<>(Arrays.asList(mq ? MQ : WG));
        Collections.shuffle(pools, random);
        int[] ports = mq ? MQ_PORT : WG_PORT;
        int offset = random.nextInt(ports.length);
        for (int i = 0; out.size() < limit && i < limit * 2; i++) {
            if (i < p.endpoints.size()) out.add(p.endpoints.get(i));
            if (i == p.endpoints.size()) out.add(mq ? "162.159.198.2:443" : "162.159.192.1:2408");
            if (out.size() >= limit) break;
            int subnet = i % pools.size();
            String ip = pools.get(subnet) + (mq ? "" : "." + (1 + random.nextInt(254)));
            // Vary ports on subsequent visits to a subnet; for MASQUE this visits all 4 x 7 pairs.
            out.add(ip + ":" + ports[(offset + subnet + i / pools.size()) % ports.length]);
        }
        return new ArrayList<>(out);
    }

    static final class Score implements Comparable<Score> {
        final String endpoint;
        final int successes, attempts;
        final long ms, time;
        Score(String endpoint, int successes, int attempts, long ms, long time) {
            if (successes < 1 || attempts < successes || attempts > ScanIntensity.HIGH.samples || ms <= 0 || ms > 120000)
                throw new IllegalArgumentException("Invalid measurement");
            this.endpoint=endpoint; this.successes=successes; this.attempts=attempts; this.ms=ms; this.time=time;
        }
        boolean fresh(long now) { return time > 0 && time <= now && now - time < TTL; }
        public int compareTo(Score o) {
            int loss = Integer.compare(o.successes * attempts, successes * o.attempts);
            if (loss != 0) return loss;
            int latency = Long.compare(ms,o.ms);
            return latency != 0 ? latency : endpoint.compareTo(o.endpoint);
        }
    }
}
