package com.leo.trashvpn;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Optional metadata from the same trace response that verified the VPN. */
final class ExitLocation {
    static final ExitLocation UNKNOWN=new ExitLocation("","");
    private static final Set<String> COUNTRIES=new HashSet<>(Arrays.asList(Locale.getISOCountries()));
    private static final int REGIONAL_A=0x1f1e6;
    final String countryCode, dataCenter;

    private ExitLocation(String countryCode,String dataCenter) {
        this.countryCode=countryCode;
        this.dataCenter=dataCenter;
    }

    static ExitLocation parse(String body) {
        String country=null, colo=null;
        for(String line:body.split("\\r?\\n")) {
            if(line.startsWith("loc=")) country=uniqueCode(country,line.substring(4),2);
            else if(line.startsWith("colo=")) colo=uniqueCode(colo,line.substring(5),3);
        }
        return new ExitLocation(COUNTRIES.contains(country)?country:"",colo==null?"":colo);
    }

    String flag() {
        if(countryCode.isEmpty()) return "";
        return new StringBuilder().appendCodePoint(REGIONAL_A+countryCode.charAt(0)-'A')
                .appendCodePoint(REGIONAL_A+countryCode.charAt(1)-'A').toString();
    }

    private static String uniqueCode(String previous,String value,int length) {
        if(previous!=null || value.length()!=length) return "";
        for(int i=0;i<length;i++) if(value.charAt(i)<'A' || value.charAt(i)>'Z') return "";
        return value;
    }
}
