package com.leo.trashvpn;

import java.util.*;
import java.util.regex.Pattern;

// Small mobile scan batches. Only WARP IPv4 prefixes and protocol-specific UDP ports
// are legal targets, including when reading persisted endpoint history.
final class NodeSelection {
    static final long TTL = 7L * 24 * 3600 * 1000;
    static final int AUTO = -1, RESCAN = -2;
    private static final Pattern ENDPOINT = Pattern.compile("[0-9.]{7,15}:[1-9][0-9]{0,4}");
    private static final String[] WG = {"8.6.112","8.34.70","8.34.146","8.35.211","8.39.125","8.39.204",
            "8.39.214","8.47.69","162.159.192","162.159.195","188.114.96","188.114.97","188.114.98","188.114.99"};
    private static final int[] WG_PORT = {2408,854,880,500,1701,859,4500,864};
    private static final String[] MQ_SUBNETS = {"162.159.198","162.159.199"};
    private static final int[] MQ_PORT = {443,4443,8443,8095,500,4500,1701};

    static boolean allowed(WarpProfile.Protocol p, String endpoint) {
        if (endpoint == null || !ENDPOINT.matcher(endpoint).matches()) return false;
        int separator=endpoint.indexOf(':');
        String ip=endpoint.substring(0,separator);
        try { TunnelIdentity.ipv4(ip); } catch (IllegalArgumentException e) { return false; }
        boolean mq = p == WarpProfile.Protocol.MASQUE;
        int lastDot=ip.lastIndexOf('.');
        String subnet=ip.substring(0,lastDot);
        if (!Arrays.asList(mq ? MQ_SUBNETS : WG).contains(subnet)) return false;
        if (mq && (ip.endsWith(".0") || ip.endsWith(".255"))) return false;
        int targetPort=Integer.parseInt(endpoint.substring(separator+1));
        for (int port : mq ? MQ_PORT : WG_PORT) if (port==targetPort) return true;
        return false;
    }

    static List<String> candidates(WarpProfile.Protocol p, List<String> history, Random random, ScanIntensity level) {
        return candidates(p,history,random,level,level.candidates);
    }
    static List<String> candidates(WarpProfile.Protocol p,List<String> history,Random random,ScanIntensity level,int limit) {
        if (limit<=0) return Collections.emptyList();
        if (p==WarpProfile.Protocol.MASQUE)
            return masqueCandidates(history,random,level,Math.min(limit,level.poolSize()));
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String ep : history) if (allowed(p,ep) && out.size()<Math.min(level.historyHints,limit)) out.add(ep);
        List<String> pools = new ArrayList<>(Arrays.asList(WG));
        Collections.shuffle(pools, random);
        int[] ports = WG_PORT;
        int offset = random.nextInt(ports.length);
        for (int i = 0; out.size() < limit && i < limit * 2; i++) {
            if (i < p.endpoints.size()) out.add(p.endpoints.get(i));
            if (i == p.endpoints.size()) out.add("162.159.192.1:2408");
            if (out.size() >= limit) break;
            int subnet = i % pools.size();
            String ip = pools.get(subnet) + "." + (1 + random.nextInt(254));
            out.add(ip + ":" + ports[(offset + subnet + i / pools.size()) % ports.length]);
        }
        return new ArrayList<>(out);
    }

    private static List<String> masqueCandidates(List<String> history,Random random,ScanIntensity level,int limit) {
        WarpProfile.Protocol p=WarpProfile.Protocol.MASQUE;
        Set<String> out=new LinkedHashSet<>();
        for (String ep:history) {
            if (out.size()>=Math.min(level.goal,limit)) break;
            if (allowed(p,ep)) out.add(ep);
        }
        for (String ep:p.endpoints) if (out.size()<limit) out.add(ep);
        List<String> gateways=new ArrayList<>();
        for (int host=2; host>=1; host--) for (String subnet:MQ_SUBNETS) {
            String ip=subnet+"."+host;
            gateways.add(ip);
            if (out.size()<limit && p.endpoints.stream().noneMatch(ep->ep.startsWith(ip+":"))) out.add(ip+":443");
        }
        if (out.size()==limit) return new ArrayList<>(out);

        // Sample only enough distinct hosts to fill the tier's endpoint-pair cap.
        // Keep known .1/.2 gateways separate; don't sample .0/.255.
        int pairsPerRound=MQ_SUBNETS.length*MQ_PORT.length;
        int count=(limit+pairsPerRound-1)/pairsPerRound; // 4 / 8 / 16 random IPs per subnet in a full plan
        int[][] samples=new int[MQ_SUBNETS.length][252];
        for (int[] hosts:samples) for (int j=0; j<hosts.length; j++) hosts[j]=j+3;
        List<String> addresses=new ArrayList<>();
        for (int i=0; i<count; i++) for (int subnet=0; subnet<MQ_SUBNETS.length; subnet++) {
            int[] hosts=samples[subnet];
            int j=i+random.nextInt(hosts.length-i);
            int chosen=hosts[j];
            hosts[j]=hosts[i];
            hosts[i]=chosen; // partial Fisher-Yates, without replacement
            addresses.add(MQ_SUBNETS[subnet]+"."+chosen);
        }
        List<String> alternates=new ArrayList<>();
        for (int wave=0; wave<MQ_PORT.length; wave++) for (int i=0; i<gateways.size(); i++) {
            String ep=gateways.get(i)+":"+MQ_PORT[(i+wave)%MQ_PORT.length];
            if (!out.contains(ep)) alternates.add(ep);
        }
        int offset=random.nextInt(MQ_PORT.length);
        int nextGateway=0;
        for (int wave=0; wave<MQ_PORT.length && out.size()<limit; wave++) {
            for (int i=0; i<addresses.size() && out.size()<limit; i+=MQ_SUBNETS.length) {
                // Do not bury the reachable gateways' alternate ports behind waves of unknown IPs.
                if (nextGateway<alternates.size()) out.add(alternates.get(nextGateway++));
                for (int j=i; j<i+MQ_SUBNETS.length && out.size()<limit; j++)
                    out.add(addresses.get(j)+":"+MQ_PORT[(offset+j+wave)%MQ_PORT.length]);
            }
        }
        return new ArrayList<>(out);
    }

    static final class Score implements Comparable<Score> {
        private static final int MAX_LATENCY_MS = 120_000;
        private static final int JITTER_BAND_MS = 25;
        final String endpoint;
        final int successes, attempts;
        final long ms, p90Ms, time;
        final double medianMs, jitterMs;
        private final long[] samples;

        /** Legacy cache rows had a mean and counts, but no observed distribution. */
        Score(String endpoint, int successes, int attempts, long ms, long time) {
            this(endpoint,successes,attempts,ms,-1,-1,-1,time,null);
        }

        private Score(String endpoint,int successes,int attempts,long ms,long p90Ms,
                      double medianMs,double jitterMs,long time,long[] samples) {
            if (successes<1 || attempts<successes || attempts>ScanIntensity.HIGH.samples || ms<=0 || ms>MAX_LATENCY_MS)
                throw new IllegalArgumentException("Invalid measurement");
            this.endpoint=endpoint; this.successes=successes; this.attempts=attempts; this.ms=ms; this.time=time;
            this.p90Ms=p90Ms; this.medianMs=medianMs; this.jitterMs=jitterMs; this.samples=samples;
        }

        static Score measured(String endpoint,long[] samples,long time) {
            if (samples.length==0 || samples.length>ScanIntensity.HIGH.samples)
                throw new IllegalArgumentException("Invalid sample count");
            long[] raw=samples.clone();
            double[] sorted=new double[raw.length];
            int ok=0;
            long total=0;
            for (long sample:raw) {
                if (sample<0 || sample>MAX_LATENCY_MS) throw new IllegalArgumentException("Invalid HTTPS sample");
                if (sample>0) { sorted[ok++]=sample; total+=sample; }
            }
            if (ok==0) throw new IllegalArgumentException("No successful HTTPS sample");
            Arrays.sort(sorted,0,ok);
            double median=median(sorted,ok);
            long p90=(long)sorted[(9*ok+9)/10-1]; // nearest rank; conservative for tiny samples
            for (int i=0;i<ok;i++) sorted[i]=Math.abs(sorted[i]-median);
            Arrays.sort(sorted,0,ok);
            double jitter=ok>=2 ? median(sorted,ok) : -1; // median absolute deviation, not inflated by failures
            return new Score(endpoint,ok,raw.length,Math.round((double)total/ok),p90,median,jitter,time,raw);
        }

        private static double median(double[] sorted,int n) { return (sorted[(n-1)/2]+sorted[n/2])/2; }
        long[] samples() { return samples==null ? null : samples.clone(); }
        double successRate() { return (double)successes/attempts; }
        boolean fresh(long now) { return time > 0 && time <= now && now - time < TTL; }

        private int jitterBand() { return jitterMs<0 ? Integer.MAX_VALUE : (int)(jitterMs/JITTER_BAND_MS); }
        public int compareTo(Score o) {
            // A neutral prior (one success and one failure) tempers small samples:
            // one lucky response does not count as demonstrated 100% reliability.
            int reliability=Integer.compare((o.successes+1)*(attempts+2),(successes+1)*(o.attempts+2));
            if (reliability!=0) return reliability;
            // Compare in priority order, rather than adding multiple penalties for one slow
            // response. Coarse jitter bands keep tiny measurement differences from deciding a rank.
            int jitter=Integer.compare(jitterBand(),o.jitterBand());
            if (jitter!=0) return jitter;
            int tail=Long.compare(p90Ms>0 ? p90Ms : ms,o.p90Ms>0 ? o.p90Ms : o.ms);
            if (tail!=0) return tail;
            int mean=Long.compare(ms,o.ms);
            return mean!=0 ? mean : endpoint.compareTo(o.endpoint);
        }
    }
}
