package com.leo.trashvpn;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class SelectionVerificationTest {
    private static class Fixture {
        long now;
        final AtomicBoolean cancel=new AtomicBoolean();
        final List<Long> starts=new ArrayList<>();
        int calls;

        long[] run(long deadline,long... outcomes) throws Exception {
            return SelectionVerification.measure(ScanIntensity.LOW,60_000,deadline,cancel,()->now,
                    wait->now+=wait,timeout->{
                        assertTrue(timeout<=7_000);
                        starts.add(now);
                        long result=outcomes[calls++];
                        now+=result>0 ? result : timeout;
                        return result;
                    });
        }
    }

    @Test public void finalRequestUsesSameProbeAfterFirstSuccessHasAged() throws Exception {
        Fixture f=new Fixture();
        assertArrayEquals(new long[]{100,70,120},f.run(30_000,100,70,120));
        assertEquals(Arrays.asList(0L,100L,2_100L),f.starts);
        assertEquals(2_220,f.now);
    }

    @Test public void timeOfIntermediateRequestsCountsTowardsWindow() throws Exception {
        Fixture f=new Fixture();
        assertArrayEquals(new long[]{100,0,120},f.run(30_000,100,0,120));
        assertEquals(Arrays.asList(0L,100L,7_100L),f.starts);
    }

    @Test public void failedConfirmationAndOneOffSuccessAreRejected() throws Exception {
        assertNull(new Fixture().run(30_000,120,130,0));
        Fixture f=new Fixture();
        assertNull(f.run(30_000,0,0)); // final request cannot yield the two required successes
        assertEquals(2,f.calls);
    }

    @Test public void budgetCutoffDoesNotBecomeANodeFailure() {
        Fixture beforeWindow=new Fixture();
        assertThrows(SelectionVerification.BudgetReached.class,()->beforeWindow.run(2_000,100,70));
        assertEquals(2,beforeWindow.calls);
        Fixture duringConfirm=new Fixture();
        assertThrows(SelectionVerification.BudgetReached.class,()->duringConfirm.run(2_150,100,70,0));
        assertEquals(3,duringConfirm.calls);
    }

    @Test public void cancellationDuringWindowDoesNotAttemptConfirmation() {
        Fixture f=new Fixture();
        assertThrows(InterruptedException.class,()->SelectionVerification.measure(ScanIntensity.MEDIUM,7_000,
                30_000,f.cancel,()->f.now,wait->f.cancel.set(true),timeout->{f.calls++; return 10;}));
        assertEquals(3,f.calls);
    }
}
