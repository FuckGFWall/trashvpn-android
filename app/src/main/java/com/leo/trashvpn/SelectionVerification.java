package com.leo.trashvpn;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Serial HTTPS samples on one candidate's existing outbound. No core or Android networking API here. */
final class SelectionVerification {
    private static final long STABILITY_WINDOW_MS = 2_000;
    static final class BudgetReached extends Exception {}
    interface Probe { long run(int timeoutMs); } // 0 = no verified response
    interface Pause { void sleep(long millis) throws InterruptedException; }

    static long[] measure(ScanIntensity level,int configuredTimeoutMs,long deadline,AtomicBoolean cancel,
                          LongSupplier clock,Pause pause,Probe probe) throws InterruptedException,BudgetReached {
        // Bound one probe by its share of the tier budget, even with a long user timeout.
        // Leave a timeout slot for coarse work and room for the delayed confirmation.
        long limit = Math.min(configuredTimeoutMs,(level.seconds*1_000L-STABILITY_WINDOW_MS)/(level.samples+1));
        long[] samples = new long[level.samples];
        long confirmAt = 0;
        int successes = 0;
        for (int i=0; i<samples.length; i++) {
            check(cancel);
            boolean last = i==samples.length-1;
            if (last) {
                // Confirmation must start at least 2s after the first successful response.
                // Earlier requests count towards this window; waiting is not measured as latency.
                if (confirmAt>=deadline) throw new BudgetReached();
                long wait;
                while ((wait=confirmAt-clock.getAsLong())>0) {
                    check(cancel);
                    pause.sleep(Math.min(wait,100));
                }
            }
            check(cancel);
            long remaining = deadline-clock.getAsLong();
            if (remaining<=0) throw new BudgetReached();
            long latency = probe.run((int)Math.min(remaining,limit));
            check(cancel);
            long end = clock.getAsLong();
            // A scan cut short is not a failed HTTPS sample; replay this candidate on resume.
            if (end>=deadline || (remaining<limit && latency==0)) throw new BudgetReached();
            samples[i]=latency;
            if (latency>0) {
                if (successes==0) confirmAt=end+STABILITY_WINDOW_MS;
                successes++;
            }
            if (successes+samples.length-i-1<2) return null;
        }
        // Never cache a one-off success or a path that already stopped passing traffic.
        return samples[samples.length-1]>0 ? samples : null;
    }

    private static void check(AtomicBoolean cancel) throws InterruptedException {
        if (cancel.get()) throw new InterruptedException();
    }
}
