package com.leo.trashvpn;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record ReleaseVersion(int major,int minor,int patch) implements Comparable<ReleaseVersion> {
    private static final Pattern TAG=Pattern.compile("[vV]?(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");

    static ReleaseVersion parse(String name) {
        if(name==null || name.length()>96) return null;
        Matcher m=TAG.matcher(name);
        return m.matches()?new ReleaseVersion(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))):null;
    }

    /** Returns only a strictly newer, stable release; a malformed API response is a failed check. */
    static String newerTag(String installed,String response) throws IOException {
        try {
            ReleaseVersion current=parse(installed);
            JSONObject json=new JSONObject(response);
            if(json.optBoolean("draft") || json.optBoolean("prerelease")) return null;
            String tag=json.getString("tag_name");
            ReleaseVersion latest=parse(tag);
            if(latest==null) throw new IOException("Invalid release tag");
            return current!=null && latest.compareTo(current)>0?tag:null;
        } catch(JSONException e) { throw new IOException("Invalid releases response",e); }
    }

    @Override public int compareTo(ReleaseVersion other) {
        int order=Integer.compare(major,other.major);
        if(order==0) order=Integer.compare(minor,other.minor);
        return order!=0?order:Integer.compare(patch,other.patch);
    }
}
