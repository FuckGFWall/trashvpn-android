package com.leo.trashvpn;

import java.util.ArrayList;
import java.util.List;

/** Desktop-style best-first connection, bounded even when every cached node is down. */
final class RankedFallback {
    static final int LIMIT = 5;
    interface Attempt { boolean verify(String endpoint, int position, int total) throws Exception; }
    interface Persist { void save(List<NodeSelection.Score> remaining) throws Exception; }
    static boolean connect(List<NodeSelection.Score> ranked, Attempt attempt, Persist persist) throws Exception {
        List<NodeSelection.Score> remaining = new ArrayList<>(ranked);
        int total = Math.min(ranked.size(), LIMIT);
        for (int i=0;i<total;i++) {
            NodeSelection.Score score = ranked.get(i);
            if (attempt.verify(score.endpoint,i+1,total)) return true;
            remaining.remove(score);
            persist.save(remaining);
        }
        return false;
    }
}
