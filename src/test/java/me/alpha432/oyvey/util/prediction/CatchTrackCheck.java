package me.alpha432.oyvey.util.prediction;

import me.alpha432.oyvey.util.prediction.CatchTrack.Outcome;
import me.alpha432.oyvey.util.prediction.CatchTrack.Phase;

import java.util.Random;

/**
 * Standalone checks for {@link CatchTrack}: no Minecraft, no JUnit. Run with
 * {@code java -cp <out> me.alpha432.oyvey.util.prediction.CatchTrackCheck}; exits non-zero on failure.
 *
 * <p>These exercise the rules (retry limit, timeouts, outcome classification, delay handling). They do not prove
 * that the game produces the evidence the rules expect; that needs in-game verification.
 */
public final class CatchTrackCheck {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        initialSuccessNoRetry();
        failureThenRetrySuccess();
        bothFailNoThirdAttempt();
        tickDelayIsTheActualSchedulingDelay();
        retryUsesTheSameDelay();
        sliderChangeAppliesToPendingAndKeepsCounters();
        pendingAttemptIsInvalidatedWhenIneligible();
        delayedConfirmationDoesNotRetry();
        confirmationBoundary();
        lateReactionCancelsPendingRetry();
        burstFarIsConfirmedFailure();
        noChargeIsUnknownNotFailure();
        unknownNeverRetriesAndEnds();
        unknownResolvedByLateEvidence();
        refusalsDoNotConsumeAttempts();
        pearlGoneClassification();
        independentTracks();
        tickDelayConfigFallback();
        sliderMappingMath();
        fuzzInvariants();

        System.out.println();
        System.out.println(checks + " checks, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ helpers

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "  PASS " : "  FAIL ") + what);
    }

    /** Schedules at {@code at}, executes at the first due tick; returns the execution tick. */
    private static long scheduleAndExecute(CatchTrack t, long at, int delay, int ping, int flight) {
        check(t.schedule(at), "scheduled at t=" + at);
        long now = at;
        while (!t.isDue(now, delay)) now++;
        t.onExecuted(now, ping, flight);
        return now;
    }

    /** Charge seen, bursts near the pearl, pearl reacts; returns the tick of the burst. */
    private static long chargeBurstsNear(CatchTrack t, long executedAt, boolean reacts) {
        long seen = executedAt + 3;
        t.onChargeSeen(seen);
        long gone = seen + 5;
        t.onChargeGone(gone, true);
        if (reacts) t.onReaction(gone + 1);
        for (long n = executedAt; n <= gone + 2; n++) t.tick(n);
        return gone;
    }

    private static void runUntilSettled(CatchTrack t, long from, long to) {
        for (long n = from; n <= to; n++) t.tick(n);
    }

    // ------------------------------------------------------------------ retry limit

    static void initialSuccessNoRetry() {
        System.out.println("initial attempt succeeds: no retry");
        CatchTrack t = new CatchTrack();
        long ex = scheduleAndExecute(t, 0, 2, 2, 8);
        chargeBurstsNear(t, ex, true);
        check(t.phase() == Phase.FINISHED && t.outcome() == Outcome.SUCCESS, "finished with SUCCESS");
        check(t.attempts() == 1, "exactly one attempt");
        check(!t.schedule(50) && !t.isDue(50, 2), "no further scheduling or execution");
    }

    static void failureThenRetrySuccess() {
        System.out.println("initial fails, retry succeeds: exactly two attempts");
        CatchTrack t = new CatchTrack();
        long ex = scheduleAndExecute(t, 0, 2, 2, 8);
        long gone = chargeBurstsNear(t, ex, false);
        runUntilSettled(t, gone + 3, gone + 10);
        check(t.phase() == Phase.RETRY_PENDING && t.outcome() == Outcome.FAILED, "confirmed failure schedules the retry");
        check(t.attempts() == 1, "retry not executed yet");
        long failedAt = gone + CatchTrack.CONFIRM_TICKS + 1;
        long retryAt = Math.max(failedAt, gone + 3) + 2;
        while (!t.isDue(retryAt, 2)) retryAt++;
        t.onExecuted(retryAt, 2, 8);
        chargeBurstsNear(t, retryAt, true);
        check(t.phase() == Phase.FINISHED && t.outcome() == Outcome.SUCCESS && t.attempts() == 2, "retry success finishes the pearl");
    }

    static void bothFailNoThirdAttempt() {
        System.out.println("both attempts fail: no third attempt");
        CatchTrack t = new CatchTrack();
        long ex = scheduleAndExecute(t, 0, 2, 0, 8);
        long gone = chargeBurstsNear(t, ex, false);
        runUntilSettled(t, gone + 3, gone + 8);
        long retryAt = gone + 8;
        while (!t.isDue(retryAt, 2)) retryAt++;
        t.onExecuted(retryAt, 0, 8);
        long gone2 = chargeBurstsNear(t, retryAt, false);
        runUntilSettled(t, gone2 + 3, gone2 + 10);
        check(t.phase() == Phase.FINISHED && t.outcome() == Outcome.FAILED, "second failure finishes with FAILED");
        check(t.attempts() == CatchTrack.MAX_ATTEMPTS, "two attempts in total");
        check(!t.schedule(500) && !t.isDue(500, 1), "a third attempt can never be scheduled or due");
        boolean threw = false;
        try {
            t.onExecuted(501, 0, 5);
        } catch (IllegalStateException e) {
            threw = true;
        }
        check(threw, "executing a third attempt is rejected outright");
    }

    // ------------------------------------------------------------------ tick delay

    static void tickDelayIsTheActualSchedulingDelay() {
        System.out.println("Tick Delay is the scheduling delay (1, 2, 5, 10)");
        for (int delay : new int[]{1, 2, 5, 10}) {
            CatchTrack t = new CatchTrack();
            t.schedule(100);
            check(!t.isDue(100 + delay - 1, delay) && t.isDue(100 + delay, delay),
                    "delay " + delay + ": not due at +" + (delay - 1) + ", due at +" + delay + " (no extra fixed delay)");
        }
    }

    static void retryUsesTheSameDelay() {
        System.out.println("the retry uses the configured delay too");
        for (int delay : new int[]{1, 2, 5, 10}) {
            CatchTrack t = new CatchTrack();
            long ex = scheduleAndExecute(t, 0, delay, 0, 6);
            long gone = chargeBurstsNear(t, ex, false);
            long n = gone + 3;
            while (t.phase() != Phase.RETRY_PENDING) t.tick(++n);
            long failedAt = n;
            check(!t.isDue(failedAt + delay - 1, delay) && t.isDue(failedAt + delay, delay),
                    "delay " + delay + ": retry due exactly " + delay + " ticks after the failure verdict");
        }
    }

    static void sliderChangeAppliesToPendingAndKeepsCounters() {
        System.out.println("changing the slider applies to a pending attempt and does not reset counters");
        CatchTrack t = new CatchTrack();
        t.schedule(0);
        check(!t.isDue(3, 5), "delay 5: not due at +3");
        check(t.isDue(3, 2), "slider moved to 2: due at +3");
        long ex = 3;
        t.onExecuted(ex, 0, 6);
        long gone = chargeBurstsNear(t, ex, false);
        runUntilSettled(t, gone + 3, gone + 8);
        int attemptsBefore = t.attempts();
        for (int delay = 1; delay <= 10; delay++) t.isDue(gone + 20, delay);
        check(t.attempts() == attemptsBefore && t.phase() == Phase.RETRY_PENDING, "attempt counter and phase untouched by delay changes");
    }

    static void pendingAttemptIsInvalidatedWhenIneligible() {
        System.out.println("a pending attempt is cancelled when the pearl becomes ineligible");
        CatchTrack t = new CatchTrack();
        t.schedule(0);
        t.invalidatePending();
        check(t.phase() == Phase.TRACKING && t.attempts() == 0, "back to TRACKING, no attempt consumed");
        check(!t.isDue(100, 1), "nothing is due while tracking");
        check(t.schedule(10) && t.isDue(12, 2), "it can be scheduled again later");
    }

    // ------------------------------------------------------------------ confirmation

    static void delayedConfirmationDoesNotRetry() {
        System.out.println("confirmation several ticks late does not trigger a retry");
        CatchTrack t = new CatchTrack();
        long ex = scheduleAndExecute(t, 0, 2, 3, 8);
        t.onChargeSeen(ex + 4);
        t.onChargeGone(ex + 10, true);
        for (long n = ex; n <= ex + 12; n++) t.tick(n);
        check(t.phase() == Phase.AWAITING_CONFIRMATION, "still waiting after the burst, no verdict yet");
        t.onReaction(ex + 13);
        t.tick(ex + 13);
        check(t.phase() == Phase.FINISHED && t.outcome() == Outcome.SUCCESS && t.attempts() == 1, "late reaction confirms success, still one attempt");
    }

    static void confirmationBoundary() {
        System.out.println("confirmation at the timeout boundary");
        int[] offsets = {CatchTrack.CONFIRM_TICKS, CatchTrack.CONFIRM_TICKS + 1};
        for (int off : offsets) {
            CatchTrack t = new CatchTrack();
            long ex = scheduleAndExecute(t, 0, 2, 2, 8);
            t.onChargeSeen(ex + 3);
            long gone = ex + 8;
            t.onChargeGone(gone, true);
            for (long n = ex; n < gone + off; n++) t.tick(n);
            t.onReaction(gone + off);       // the module records the reaction before ticking
            t.tick(gone + off);
            check(t.outcome() == Outcome.SUCCESS, "reaction " + off + " ticks after the burst is accepted as success");
        }
        CatchTrack t = new CatchTrack();
        long ex = scheduleAndExecute(t, 0, 2, 2, 8);
        t.onChargeSeen(ex + 3);
        long gone = ex + 8;
        t.onChargeGone(gone, true);
        for (long n = ex; n <= gone + CatchTrack.CONFIRM_TICKS + 1; n++) t.tick(n);
        check(t.phase() == Phase.RETRY_PENDING && t.outcome() == Outcome.FAILED, "no reaction by the end of the window: confirmed failure");
    }

    static void lateReactionCancelsPendingRetry() {
        System.out.println("a late success cancels the retry before it executes");
        CatchTrack t = new CatchTrack();
        long ex = scheduleAndExecute(t, 0, 5, 2, 8);
        t.onChargeSeen(ex + 3);
        long gone = ex + 8;
        t.onChargeGone(gone, true);
        long n = ex;
        for (; t.phase() != Phase.RETRY_PENDING; n++) t.tick(n);
        check(t.phase() == Phase.RETRY_PENDING, "failure verdict reached, retry pending");
        t.onReaction(n + 1);
        t.tick(n + 1);
        check(t.phase() == Phase.FINISHED && t.outcome() == Outcome.SUCCESS, "late reaction turns it into SUCCESS");
        check(!t.isDue(n + 50, 5) && t.attempts() == 1, "the retry never executes");
        // far outside the late window it must not count
        CatchTrack u = new CatchTrack();
        long ex2 = scheduleAndExecute(u, 0, 10, 2, 8);
        u.onChargeSeen(ex2 + 3);
        u.onChargeGone(ex2 + 8, true);
        long m = ex2;
        for (; u.phase() != Phase.RETRY_PENDING; m++) u.tick(m);
        u.onReaction(ex2 + 8 + CatchTrack.LATE_REACTION_TICKS + 5);
        u.tick(ex2 + 8 + CatchTrack.LATE_REACTION_TICKS + 5);
        check(u.phase() == Phase.RETRY_PENDING, "a much later velocity change is not attributed to the burst");
    }

    static void burstFarIsConfirmedFailure() {
        System.out.println("a burst far from the pearl is a confirmed failure");
        CatchTrack t = new CatchTrack();
        long ex = scheduleAndExecute(t, 0, 2, 1, 8);
        t.onChargeSeen(ex + 2);
        t.onChargeGone(ex + 7, false);
        t.onReaction(ex + 7);                         // an unrelated push must not rescue it
        t.tick(ex + 7);
        check(t.phase() == Phase.RETRY_PENDING && t.outcome() == Outcome.FAILED, "FAILED at once, retry pending");
    }

    static void noChargeIsUnknownNotFailure() {
        System.out.println("no confirmation before the timeout is UNKNOWN, not a failure");
        CatchTrack t = new CatchTrack();
        long ex = scheduleAndExecute(t, 0, 2, 2, 8);
        long deadline = ex + 2 + CatchTrack.SPAWN_MARGIN_TICKS;
        for (long n = ex; n <= deadline; n++) t.tick(n);
        check(t.phase() == Phase.AWAITING_CONFIRMATION, "still waiting at the deadline itself");
        t.tick(deadline + 1);
        check(t.phase() == Phase.OUTCOME_UNKNOWN && t.outcome() == Outcome.UNKNOWN, "OUTCOME_UNKNOWN after the deadline");
        check(!t.isDue(deadline + 100, 1) && !t.schedule(deadline + 2), "no retry is scheduled for an unknown outcome");
    }

    static void unknownNeverRetriesAndEnds() {
        System.out.println("an unresolved unknown outcome ends quietly after the grace period");
        CatchTrack t = new CatchTrack();
        long ex = scheduleAndExecute(t, 0, 2, 2, 8);
        long n = ex;
        for (; t.phase() != Phase.OUTCOME_UNKNOWN; n++) t.tick(n);
        boolean everDue = false;
        for (long k = n; k <= n + CatchTrack.UNKNOWN_GRACE_TICKS + 2; k++) {
            t.tick(k);
            everDue |= t.isDue(k, 1);
        }
        check(!everDue, "never due for another throw while the outcome is unknown");
        check(t.phase() == Phase.FINISHED && t.outcome() == Outcome.UNKNOWN && t.attempts() == 1, "FINISHED/UNKNOWN with one attempt");
    }

    static void unknownResolvedByLateEvidence() {
        System.out.println("an unknown outcome is resolved by later reliable evidence");
        CatchTrack s = new CatchTrack();
        long ex = scheduleAndExecute(s, 0, 2, 2, 8);
        long n = ex;
        for (; s.phase() != Phase.OUTCOME_UNKNOWN; n++) s.tick(n);
        s.onChargeSeen(n + 1);
        s.onChargeGone(n + 2, true);
        s.onReaction(n + 3);
        s.tick(n + 3);
        check(s.outcome() == Outcome.SUCCESS && s.phase() == Phase.FINISHED, "late charge + reaction -> SUCCESS");

        CatchTrack f = new CatchTrack();
        long ex2 = scheduleAndExecute(f, 0, 2, 2, 8);
        long m = ex2;
        for (; f.phase() != Phase.OUTCOME_UNKNOWN; m++) f.tick(m);
        f.onChargeSeen(m + 1);
        f.onChargeGone(m + 2, false);
        f.tick(m + 2);
        check(f.outcome() == Outcome.FAILED && f.phase() == Phase.RETRY_PENDING && f.attempts() == 1, "late far burst -> confirmed FAILED, the one retry becomes available");
    }

    static void refusalsDoNotConsumeAttempts() {
        System.out.println("client refusals do not consume attempts and are bounded");
        CatchTrack t = new CatchTrack();
        t.schedule(0);
        t.onRefused(2);
        check(t.attempts() == 0 && t.phase() == Phase.ATTEMPT_PENDING, "still pending, zero attempts used");
        check(!t.isDue(3, 1) && t.isDue(2 + CatchTrack.REFUSAL_BACKOFF_TICKS, 1), "backs off before trying again");
        t.onRefused(10);
        t.onRefused(20);
        check(t.phase() == Phase.FINISHED && t.attempts() == 0, "gives up after " + CatchTrack.MAX_REFUSALS + " refusals");
    }

    static void pearlGoneClassification() {
        System.out.println("pearl disappearing is not treated as success by itself");
        CatchTrack a = new CatchTrack();
        long ex = scheduleAndExecute(a, 0, 2, 2, 8);
        a.onPearlGone(ex + 3);
        check(a.outcome() == Outcome.UNKNOWN && a.phase() == Phase.FINISHED, "gone with no evidence -> UNKNOWN");

        CatchTrack b = new CatchTrack();
        long ex2 = scheduleAndExecute(b, 0, 2, 2, 8);
        b.onChargeSeen(ex2 + 2);
        b.onChargeGone(ex2 + 6, true);
        b.onReaction(ex2 + 6);
        b.onPearlGone(ex2 + 7);
        check(b.outcome() == Outcome.SUCCESS, "gone after a near burst and a reaction -> SUCCESS");

        CatchTrack c = new CatchTrack();
        long ex3 = scheduleAndExecute(c, 0, 2, 2, 8);
        c.onChargeSeen(ex3 + 2);
        c.onChargeGone(ex3 + 6, true);
        c.onPearlGone(ex3 + 7);
        check(c.outcome() == Outcome.UNKNOWN, "gone after a near burst but no reaction seen -> UNKNOWN, not FAILED");

        CatchTrack d = new CatchTrack();
        d.schedule(0);
        d.onPearlGone(5);
        check(d.phase() == Phase.FINISHED && d.attempts() == 0 && d.outcome() == Outcome.NONE, "gone while pending -> dropped, nothing executed");
    }

    static void independentTracks() {
        System.out.println("tracks are independent");
        CatchTrack a = new CatchTrack(), b = new CatchTrack();
        long exA = scheduleAndExecute(a, 0, 2, 2, 8);
        scheduleAndExecute(b, 1, 2, 2, 8);
        chargeBurstsNear(a, exA, true);
        check(a.outcome() == Outcome.SUCCESS, "A confirmed");
        check(b.phase() == Phase.AWAITING_CONFIRMATION && b.attempts() == 1 && b.outcome() == Outcome.NONE, "B unaffected by A's confirmation");
    }

    // ------------------------------------------------------------------ settings

    static void tickDelayConfigFallback() {
        System.out.println("Tick Delay: whole numbers 1..10, anything else falls back to 2");
        boolean all = true;
        for (int i = 1; i <= 10; i++) all &= CatchTrack.sanitizeTickDelay(i) == i && CatchTrack.parseTickDelay(String.valueOf(i)) == i;
        check(all, "every value from 1 to 10 is accepted unchanged");
        check(CatchTrack.DEFAULT_TICK_DELAY == 2, "default is 2");
        String[] bad = {null, "", "abc", "2.5", "0", "-3", "11", "99", "2,0", "NaN"};
        boolean ok = true;
        for (String s : bad) ok &= CatchTrack.parseTickDelay(s) == 2;
        check(ok, "missing / non-numeric / fractional / out-of-range saved values fall back to 2");
        check(CatchTrack.parseTickDelay(" 7 ") == 7, "surrounding whitespace is tolerated");
    }

    /** Replicates the ClickGUI Integer slider mapping to show why the truncating version could not reach the maximum. */
    static void sliderMappingMath() {
        System.out.println("ClickGUI integer slider mapping (1..10)");
        int min = 1, max = 10, diff = max - min;
        boolean[] oldHit = new boolean[11], newHit = new boolean[11];
        int pixels = 224;                                     // bar is ~22.4 wide; sample at 0.1 px
        for (int i = 0; i <= pixels; i++) {
            float percent = i / 224f * 1.027f;                // hover area extends slightly past the bar
            int oldV = Math.max(min, Math.min(max, min + (int) ((float) diff * percent)));
            int newV = Math.max(min, Math.min(max, min + Math.round((float) diff * percent)));
            oldHit[oldV] = true;
            newHit[newV] = true;
        }
        boolean allNew = true;
        for (int v = min; v <= max; v++) allNew &= newHit[v];
        check(allNew, "rounding reaches every value 1..10");
        int oldPixelsAtTen = 0, newPixelsAtTen = 0;
        for (int i = 0; i <= pixels; i++) {
            float percent = i / 224f * 1.027f;
            if (min + (int) ((float) diff * percent) >= max) oldPixelsAtTen++;
            if (min + Math.round((float) diff * percent) >= max) newPixelsAtTen++;
        }
        check(newPixelsAtTen > oldPixelsAtTen, "the top value covers " + newPixelsAtTen + " sample points instead of " + oldPixelsAtTen);
    }

    // ------------------------------------------------------------------ fuzz

    static void fuzzInvariants() {
        System.out.println("random event streams never break the retry limit");
        Random rnd = new Random(7);
        int runs = 30000, violations = 0, finishedThenMoved = 0, executions = 0;
        for (int r = 0; r < runs; r++) {
            CatchTrack t = new CatchTrack();
            long now = 0;
            int delay = 1 + rnd.nextInt(10);
            int executedByDriver = 0;
            for (int step = 0; step < 400; step++) {
                now += rnd.nextInt(3);
                if (rnd.nextInt(60) == 0) delay = 1 + rnd.nextInt(10);
                boolean finishedBefore = t.isFinished();
                int attemptsBefore = t.attempts();
                switch (rnd.nextInt(10)) {
                    case 0 -> t.schedule(now);
                    case 1, 2 -> {
                        if (t.isDue(now, delay)) {
                            t.onExecuted(now, rnd.nextInt(8), rnd.nextInt(30));
                            executedByDriver++;
                        }
                    }
                    case 3 -> t.onChargeSeen(now);
                    case 4 -> t.onChargeGone(now, rnd.nextBoolean());
                    case 5 -> t.onReaction(now);
                    case 6 -> t.invalidatePending();
                    case 7 -> t.onRefused(now);
                    case 8 -> {
                        if (rnd.nextInt(40) == 0) t.onPearlGone(now);
                    }
                    default -> t.tick(now);
                }
                t.tick(now);
                if (t.attempts() > CatchTrack.MAX_ATTEMPTS) violations++;
                if (t.attempts() < attemptsBefore) violations++;
                if (finishedBefore && (!t.isFinished() || t.attempts() != attemptsBefore)) finishedThenMoved++;
            }
            executions += executedByDriver;
            if (executedByDriver > CatchTrack.MAX_ATTEMPTS) violations++;
        }
        check(violations == 0, runs + " random runs (" + executions + " executions): attempts never exceed 2 or decrease");
        check(finishedThenMoved == 0, "a finished pearl never restarts");
    }
}
