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
                    NodeSelection.Score s=new NodeSelection.Score(ep,o.getInt("ok"),o.getInt("n"),
                            o.getLong("ms"),o.getLong("t"));
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
        for (NodeSelection.Score s:sorted) {
            if (arr.length()==level.cacheSize) break;
            arr.put(new JSONObject().put("ep",s.endpoint).put("ok",s.successes).put("n",s.attempts)
                    .put("ms",s.ms).put("t",s.time));
        }
        // Write verified results before the journal advances past the corresponding endpoint.
        if (!prefs.edit().putString(key,arr.toString()).commit()) throw new JSONException("Could not persist rankings");
    }
}
