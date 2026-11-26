package com.leo.trashvpn;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import org.json.*;

/** Persist an immutable plan and the boundary between waves. In-flight work may replay, never skip. */
final class ScanJournal {
    final List<String> plan, pending;
    int cursor;
    final long created;
    private final SharedPreferences prefs;
    private final String key;
    ScanJournal(SharedPreferences prefs,String key,List<String> plan,List<String> pending,int cursor,long created) {
        this.prefs=prefs; this.key=key; this.plan=plan; this.pending=pending; this.cursor=cursor; this.created=created;
    }
    static ScanJournal open(Context c,WarpProfile.Protocol p,ScanIntensity level,List<String> hints) throws Exception {
        SharedPreferences prefs=c.getSharedPreferences("selection-progress-v1",Context.MODE_PRIVATE);
        String key=p.name()+".true."+level.name(); // Reuse journals from locally registered devices.
        long now=System.currentTimeMillis();
        try {
            JSONObject j=new JSONObject(prefs.getString(key,"{}"));
            long created=j.getLong("created"); int cursor=j.getInt("cursor");
            List<String> plan=list(j.getJSONArray("plan"),p,level.poolSize()), pending=list(j.getJSONArray("pending"),p,level.poolSize());
            if(created>0 && now>=created && now-created<NodeSelection.TTL && cursor>=0 && cursor<=plan.size()
                    && plan.subList(0,cursor).containsAll(pending) && (cursor<plan.size() || !pending.isEmpty()))
                return new ScanJournal(prefs,key,plan,pending,cursor,created);
        } catch (JSONException | IllegalArgumentException ignored) { }
        ScanJournal fresh=new ScanJournal(prefs,key,NodeSelection.candidates(p,hints,new Random(),level,level.poolSize()),new ArrayList<>(),0,now);
        fresh.save(); return fresh;
    }
    static List<String> list(JSONArray a,WarpProfile.Protocol p,int max) throws JSONException {
        if(a.length()>max) throw new JSONException("Oversize plan");
        List<String> out=new ArrayList<>();
        for(int i=0;i<a.length();i++) {
            String ep=a.getString(i); if(!NodeSelection.allowed(p,ep)||out.contains(ep)) throw new JSONException("Invalid plan");
            out.add(ep);
        }
        return out;
    }
    void save() throws Exception {
        String value=new JSONObject().put("created",created).put("cursor",cursor)
            .put("plan",new JSONArray(plan)).put("pending",new JSONArray(pending)).toString();
        if(!prefs.edit().putString(key,value).commit()) throw new java.io.IOException("Failed to persist selection");
    }
}
