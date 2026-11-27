package com.leo.trashvpn;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import org.json.*;

/** Persist an immutable plan and the boundary between waves. In-flight work may replay, never skip. */
final class ScanJournal {
    private static final String PREFS="selection-progress-v1";
    final List<String> plan, pending;
    int cursor;
    final long created;
    private final SharedPreferences prefs;
    private final String key;
    private boolean finished;
    private ScanJournal(SharedPreferences prefs,String key,List<String> plan,List<String> pending,
                        int cursor,long created,boolean finished) {
        this.prefs=prefs; this.key=key; this.plan=Collections.unmodifiableList(plan); this.pending=pending;
        this.cursor=cursor; this.created=created; this.finished=finished;
    }
    private static String key(WarpProfile.Protocol p,ScanIntensity level) { return p.name()+".true."+level.name(); }

    static synchronized ScanJournal open(Context c,WarpProfile.Protocol p,ScanIntensity level,
                                         List<String> hints,boolean resample) throws Exception {
        SharedPreferences prefs=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String key=key(p,level); // Reuse journals from locally registered devices.
        long now=System.currentTimeMillis();
        if (!resample) {
            ScanJournal prior=read(prefs,key,p,level,now);
            if (prior!=null) {
                if (prior.finished) {
                    prior.finished=false; // Automatic fallback may continue an already successful run.
                    prior.save();
                }
                return prior;
            }
        }
        ScanJournal fresh=new ScanJournal(prefs,key,NodeSelection.candidates(p,hints,new Random(),level,level.poolSize()),
                new ArrayList<>(),0,now,false);
        fresh.save();
        return fresh;
    }

    static boolean canContinue(Context c,WarpProfile.Protocol p,ScanIntensity level) {
        SharedPreferences prefs=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        ScanJournal journal=read(prefs,key(p,level),p,level,System.currentTimeMillis());
        return journal!=null && !journal.finished;
    }

    private static ScanJournal read(SharedPreferences prefs,String key,WarpProfile.Protocol p,ScanIntensity level,long now) {
        try {
            JSONObject j=new JSONObject(prefs.getString(key,"{}"));
            long created=j.getLong("created");
            if (created<=0 || created>now || now-created>=NodeSelection.TTL) return null;
            int cursor=j.getInt("cursor");
            List<String> plan=list(j.getJSONArray("plan"),p,level.poolSize()), pending=list(j.getJSONArray("pending"),p,level.poolSize());
            if (cursor>=0 && cursor<=plan.size() && plan.subList(0,cursor).containsAll(pending)
                    && (cursor<plan.size() || !pending.isEmpty()))
                return new ScanJournal(prefs,key,plan,pending,cursor,created,j.optBoolean("finished"));
        } catch (JSONException | IllegalArgumentException ignored) { }
        return null;
    }
    private static List<String> list(JSONArray a,WarpProfile.Protocol p,int max) throws JSONException {
        if(a.length()>max) throw new JSONException("Oversize plan");
        Set<String> unique=new LinkedHashSet<>();
        for(int i=0;i<a.length();i++) {
            String ep=a.getString(i);
            if (!NodeSelection.allowed(p,ep) || !unique.add(ep)) throw new JSONException("Invalid plan");
        }
        return new ArrayList<>(unique);
    }
    void finish() throws Exception {
        finished=true;
        save();
    }
    void save() throws Exception {
        String value=new JSONObject().put("created",created).put("cursor",cursor).put("finished",finished)
            .put("plan",new JSONArray(plan)).put("pending",new JSONArray(pending)).toString();
        if(!prefs.edit().putString(key,value).commit()) throw new java.io.IOException("Failed to persist selection");
    }
}
