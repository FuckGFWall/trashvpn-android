package com.leo.trashvpn;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

final class SelectionCache {
    private final SharedPreferences prefs;
    private final String key;
    private final WarpProfile.Protocol protocol;
    private final ScanIntensity level;
    SelectionCache(Context context, WarpProfile.Protocol protocol, ScanIntensity level) {
        this.protocol = protocol;
        this.level = level;
        prefs = context.getSharedPreferences("node-scores-v2", Context.MODE_PRIVATE);
        // v0.2.0 measured two traces per candidate. Its existing data belongs to medium.
        key = protocol.name() + ".device" + (level == ScanIntensity.MEDIUM ? "" : "." + level.name());
    }
    List<NodeSelection.Score> load() {
        return load(System.currentTimeMillis());
    }
    private List<NodeSelection.Score> load(long now) {
        List<NodeSelection.Score> scores = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(key,"[]"));
            for (int i=0; i<Math.min(array.length(),level.cacheSize); i++) {
                try {
                    JSONObject o = array.getJSONObject(i);
                    String ep=o.getString("ep");
                    if (!NodeSelection.allowed(protocol,ep)) continue;
                    long time=o.getLong("t");
                    NodeSelection.Score s;
                    if (o.has("samples")) {
                        JSONArray raw=o.getJSONArray("samples");
                        if (raw.length()>ScanIntensity.HIGH.samples) continue;
                        long[] samples=new long[raw.length()];
                        for (int j=0; j<samples.length; j++) samples[j]=raw.getLong(j);
                        s=NodeSelection.Score.measured(ep,samples,time);
                    } else {
                        // No invented percentiles/jitter for data saved by older versions.
                        s=new NodeSelection.Score(ep,o.getInt("ok"),o.getInt("n"),o.getLong("ms"),time);
                    }
                    if (s.fresh(now)) scores.add(s);
                } catch (JSONException | IllegalArgumentException ignored) { }
            }
        } catch (JSONException ignored) { }
        Collections.sort(scores);
        return scores;
    }
    static List<NodeSelection.Score> loadAll(Context context,WarpProfile.Protocol protocol) {
        List<NodeSelection.Score> scores=new ArrayList<>();
        long now=System.currentTimeMillis();
        for(ScanIntensity level:ScanIntensity.values())
            scores.addAll(new SelectionCache(context,protocol,level).load(now));
        Collections.sort(scores);
        Set<String> seen=new HashSet<>();
        scores.removeIf(score->!seen.add(score.endpoint));
        return scores;
    }
    void save(List<NodeSelection.Score> scores) throws JSONException {
        List<NodeSelection.Score> sorted=new ArrayList<>(scores);
        Collections.sort(sorted);
        JSONArray arr=new JSONArray();
        Set<String> seen=new HashSet<>();
        for (NodeSelection.Score s:sorted) {
            if (arr.length()==level.cacheSize) break;
            if (!NodeSelection.allowed(protocol,s.endpoint) || !seen.add(s.endpoint)) continue;
            JSONObject row=new JSONObject().put("ep",s.endpoint).put("ok",s.successes).put("n",s.attempts)
                    .put("ms",s.ms).put("t",s.time);
            // Persist the tiny raw sample set, not a hardcoded score. Any future ranking
            // change can recompute its metrics; the original count/mean fields remain readable.
            long[] samples=s.samples();
            if (samples!=null) {
                JSONArray raw=new JSONArray();
                for (long sample:samples) raw.put(sample);
                row.put("samples",raw);
            }
            arr.put(row);
        }
        // Write verified results before the journal advances past the corresponding endpoint.
        if (!prefs.edit().putString(key,arr.toString()).commit()) throw new JSONException("Could not persist rankings");
    }
}
