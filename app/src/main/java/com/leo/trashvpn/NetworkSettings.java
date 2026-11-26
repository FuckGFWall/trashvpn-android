package com.leo.trashvpn;

import android.content.Context;
import android.content.SharedPreferences;

/** One validated snapshot per foreground scan or VPN connection. */
final class NetworkSettings {
    static final String PREFS="selection-settings";
    static final int DEFAULT_PROBE_SECONDS=7;
    static final String DEFAULT_DNS="1.1.1.1";
    static final String TIMEOUT="probe-seconds", MTU="mtu", DNS="dns", PROTECT="dns-protect", NO_DNS="no-dns";
    static final String AUTO_CONNECT="auto-connect-after-selection";

    final int probeSeconds, mtu;
    final String customDns;
    final boolean protect, noDns;

    NetworkSettings(int probeSeconds,int mtu,String dns,boolean protect,boolean noDns) {
        this.probeSeconds=timeout(String.valueOf(probeSeconds));
        this.mtu=mtu(String.valueOf(mtu));
        this.customDns=dns(dns);
        this.protect=protect; this.noDns=noDns;
    }
    static NetworkSettings defaults() { return new NetworkSettings(DEFAULT_PROBE_SECONDS,0,"",true,false); }
    static NetworkSettings load(Context c) {
        SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        return new NetworkSettings(p.getInt(TIMEOUT,DEFAULT_PROBE_SECONDS),p.getInt(MTU,0),
                p.getString(DNS,""),p.getBoolean(PROTECT,true),p.getBoolean(NO_DNS,false));
    }
    private static int integer(String value,String error) {
        try { return Integer.parseInt(value.trim()); }
        catch(NumberFormatException e) { throw new IllegalArgumentException(error); }
    }
    static int timeout(String value) {
        String error="Probe timeout out of range";
        int n=integer(value,error);
        if(n<1||n>60) throw new IllegalArgumentException(error);
        return n;
    }
    static int mtu(String value) {
        String error="MTU out of range";
        int n=integer(value,error);
        if(n!=0&&(n<576||n>9000)) throw new IllegalArgumentException(error);
        return n;
    }
    static String dns(String value) {
        String trimmed=value.trim();
        if(trimmed.isEmpty()) return "";
        StringBuilder ascii=new StringBuilder(trimmed.length());
        for(int i=0;i<trimmed.length();i++) {
            char c=trimmed.charAt(i);
            int digit=Character.digit(c,10);
            ascii.append(digit<0?c:(char)('0'+digit));
        }
        return TunnelIdentity.ipv4(ascii.toString());
    }

    int effectiveMtu(int underlyingMtu) {
        if(mtu!=0) return mtu;
        return underlyingMtu>0 ? Math.max(576,Math.min(1280,underlyingMtu-80)) : 1280;
    }
    int probeMillis() { return probeSeconds*1000; }
    boolean protectedDns() { return !noDns&&protect; }
    String resolver() { return customDns.isEmpty()?DEFAULT_DNS:customDns; }
    String advertisedDns() { return noDns?null:protectedDns()?WarpProfile.DNS:resolver(); }
    String dnsHijack() { return protectedDns()?"0.0.0.0":""; }
    String outboundResolver() { return noDns?DEFAULT_DNS:resolver(); }
    String upstream() {
        return (customDns.isEmpty() || DEFAULT_DNS.equals(customDns) ? "https://1.1.1.1/dns-query" : resolver())
                +"#"+WarpProfile.PROXY;
    }
}
