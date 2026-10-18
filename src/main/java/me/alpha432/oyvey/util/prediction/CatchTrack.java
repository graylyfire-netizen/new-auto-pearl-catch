package me.alpha432.oyvey.util.prediction;

/**
 * Attempt lifecycle for one ender pearl: scheduling, retry limit and outcome classification.
 *
 * <p>This class holds no Minecraft types. The module feeds it observations (a charge appeared, a charge vanished,
 * the pearl's velocity changed, the pearl is gone) and asks it what is allowed next, so every rule about
 * <i>how many</i> attempts may happen and <i>what counts as</i> success, failure or unknown lives in one place that
 * can be tested without the game.
 *
 * <p>Phases: {@code TRACKING} (eligible pearl, nothing scheduled) -> {@code ATTEMPT_PENDING} (scheduled, not yet
 * executed) -> {@code AWAITING_CONFIRMATION} (throw accepted, collecting evidence) -> {@code RETRY_PENDING} (confirmed
 * failure, the one permitted retry scheduled) or {@code FINISHED}; {@code OUTCOME_UNKNOWN} is entered when evidence
 * does not arrive in time and never leads to another throw by itself.
 *
 * <p>Evidence for one attempt, in order: the server spawned our charge (a wind-charge entity owned by us appeared),
 * the charge vanished (burst), the burst was close enough to affect the pearl, and the pearl's velocity then
 * departed from free flight. Sending the use-item packet proves none of these.
 */
public final class CatchTrack {
    /** One initial attempt plus one retry. */
    public static final int MAX_ATTEMPTS = 2;

    public static final int MIN_TICK_DELAY = 1;
    public static final int MAX_TICK_DELAY = 10;
    public static final int DEFAULT_TICK_DELAY = 2;

    /** Client refusals of the use (swap failed, item on cooldown) tolerated before the pearl is given up. */
    public static final int MAX_REFUSALS = 3;
    /** Wait after a refused use before trying again. */
    public static final int REFUSAL_BACKOFF_TICKS = 2;
    /** Extra ticks, beyond the round trip, for the server to spawn our charge and tell us. */
    public static final int SPAWN_MARGIN_TICKS = 6;
    /** Extra ticks, beyond the predicted flight, for the charge to reach its target and burst. */
    public static final int BURST_MARGIN_TICKS = 10;
    /**
     * Ticks after the burst is seen during which the pearl may still react. The burst and the pearl's velocity update
     * travel the same path, so they normally arrive within a tick or two of each other; this covers jitter.
     */
    public static final int CONFIRM_TICKS = 4;
    /** A reaction up to this many ticks before the burst is noticed still belongs to it (packet ordering). */
    public static final int REACTION_LOOKBACK_TICKS = 2;
    /** After a confirmed failure, a reaction within this many ticks of the burst still cancels the pending retry. */
    public static final int LATE_REACTION_TICKS = 10;
    /** How long OUTCOME_UNKNOWN keeps listening for late evidence before the pearl is finished. */
    public static final int UNKNOWN_GRACE_TICKS = 20;

    public enum Phase {
        TRACKING, ATTEMPT_PENDING, AWAITING_CONFIRMATION, RETRY_PENDING, OUTCOME_UNKNOWN, FINISHED
    }

    public enum Outcome {
        /** Nothing decided yet (or the pearl was dropped before any attempt ran). */
        NONE,
        SUCCESS,
        FAILED,
        UNKNOWN
    }

    private Phase phase = Phase.TRACKING;
    private Outcome outcome = Outcome.NONE;
    private int attempts;
    private int refusals;
    private long scheduledAt;
    private long notBefore;

    private int flightTicks;
    private long spawnDeadline;
    private long burstDeadline;
    private long unknownSince;
    private boolean chargeSeen;
    private long goneAt = -1;
    private boolean burstNear;
    private long lastReactionTick = -1;

    /** The configured value if it is a whole number from 1 to 10, otherwise the default. */
    public static int sanitizeTickDelay(int raw) {
        return raw < MIN_TICK_DELAY || raw > MAX_TICK_DELAY ? DEFAULT_TICK_DELAY : raw;
    }

    /** Parses a saved Tick Delay; anything that is not a whole number from 1 to 10 yields the default. */
    public static int parseTickDelay(String raw) {
        if (raw == null) return DEFAULT_TICK_DELAY;
        try {
            return sanitizeTickDelay(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_TICK_DELAY;
        }
    }

    public Phase phase() {
        return phase;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** Throws actually executed (0..2). */
    public int attempts() {
        return attempts;
    }

    public boolean chargeSeen() {
        return chargeSeen;
    }

    public boolean chargeGone() {
        return goneAt >= 0;
    }

    public boolean isPending() {
        return phase == Phase.ATTEMPT_PENDING || phase == Phase.RETRY_PENDING;
    }

    /** True while the last executed attempt is still collecting evidence. */
    public boolean isWaiting() {
        return phase == Phase.AWAITING_CONFIRMATION || phase == Phase.OUTCOME_UNKNOWN;
    }

    public boolean isFinished() {
        return phase == Phase.FINISHED;
    }

    // ------------------------------------------------------------------ scheduling

    /** Schedules the next attempt (the initial one, or the retry after a failure). */
    public boolean schedule(long now) {
        if (phase != Phase.TRACKING || attempts >= MAX_ATTEMPTS) return false;
        phase = attempts == 0 ? Phase.ATTEMPT_PENDING : Phase.RETRY_PENDING;
        scheduledAt = now;
        notBefore = 0;
        return true;
    }

    /**
     * Whether the scheduled attempt may execute now: {@code tickDelay} ticks after it was scheduled. The delay is
     * read on every call, so changing the setting applies to attempts that are already pending.
     */
    public boolean isDue(long now, int tickDelay) {
        return isPending() && attempts < MAX_ATTEMPTS
                && now >= Math.max(scheduledAt + sanitizeTickDelay(tickDelay), notBefore);
    }

    /** The pearl stopped being eligible (range, ownership, no solution) before the attempt ran. */
    public void invalidatePending() {
        if (isPending()) phase = Phase.TRACKING;
    }

    /** The client refused the use; no throw happened, so no attempt is consumed. */
    public void onRefused(long now) {
        if (!isPending()) return;
        notBefore = now + REFUSAL_BACKOFF_TICKS;
        if (++refusals >= MAX_REFUSALS) phase = Phase.FINISHED;
    }

    /** The client accepted the use. Consumes one attempt and starts collecting evidence. */
    public void onExecuted(long now, int pingTicks, int predictedFlightTicks) {
        if (!isPending() || attempts >= MAX_ATTEMPTS) {
            throw new IllegalStateException("attempt not allowed in phase " + phase + " after " + attempts);
        }
        attempts++;
        refusals = 0;
        phase = Phase.AWAITING_CONFIRMATION;
        outcome = Outcome.NONE;
        chargeSeen = false;
        goneAt = -1;
        burstNear = false;
        lastReactionTick = -1;
        flightTicks = Math.max(0, predictedFlightTicks);
        spawnDeadline = now + Math.max(0, pingTicks) + SPAWN_MARGIN_TICKS;
        burstDeadline = now + Math.max(0, pingTicks) + flightTicks + BURST_MARGIN_TICKS;
    }

    // ------------------------------------------------------------------ evidence

    /** The server spawned a wind charge owned by us that matches this attempt. */
    public void onChargeSeen(long now) {
        if (!isWaiting() || chargeSeen) return;
        chargeSeen = true;
        burstDeadline = Math.max(burstDeadline, now + flightTicks + BURST_MARGIN_TICKS);
    }

    /** The charge vanished (it burst); {@code nearPearl} says whether that was within reach of the pearl. */
    public void onChargeGone(long now, boolean nearPearl) {
        if (!isWaiting() || !chargeSeen || goneAt >= 0) return;
        goneAt = now;
        burstNear = nearPearl;
    }

    /** The pearl's velocity departed from free flight on this tick. */
    public void onReaction(long now) {
        lastReactionTick = now;
    }

    /** Advances timeouts and classifies the outcome when the evidence allows it. Call once per tick. */
    public void tick(long now) {
        switch (phase) {
            case AWAITING_CONFIRMATION -> {
                if (evaluateBurst(now)) return;
                if (!chargeSeen && now > spawnDeadline) markUnknown(now);
                else if (chargeSeen && goneAt < 0 && now > burstDeadline) markUnknown(now);
            }
            case OUTCOME_UNKNOWN -> {
                if (evaluateBurst(now)) return;
                if (now > unknownSince + UNKNOWN_GRACE_TICKS) finish(Outcome.UNKNOWN);
            }
            case RETRY_PENDING -> {
                // a confirmation that arrived after the failure verdict: the first attempt did work
                if (outcome == Outcome.FAILED && goneAt >= 0 && burstNear && reacted()
                        && now <= goneAt + LATE_REACTION_TICKS) {
                    finish(Outcome.SUCCESS);
                }
            }
            default -> {
            }
        }
    }

    /** The pearl entity is gone. Nothing more can be attempted on it; classify what the evidence supports. */
    public void onPearlGone(long now) {
        if (phase == Phase.FINISHED) return;
        if (isWaiting()) {
            if (goneAt >= 0 && !burstNear) outcome = Outcome.FAILED;
            else if (goneAt >= 0 && reacted()) outcome = Outcome.SUCCESS;
            else outcome = Outcome.UNKNOWN;
        }
        phase = Phase.FINISHED;
    }

    /** Stops all processing of this pearl (ownership lost, module disabled). */
    public void cancel() {
        phase = Phase.FINISHED;
    }

    // ------------------------------------------------------------------ internals

    private boolean evaluateBurst(long now) {
        if (goneAt < 0) return false;
        if (!burstNear) {
            fail(now);
            return true;
        }
        if (reacted()) {
            finish(Outcome.SUCCESS);
            return true;
        }
        if (now > goneAt + CONFIRM_TICKS) {
            fail(now);
            return true;
        }
        return false;
    }

    private boolean reacted() {
        return lastReactionTick >= goneAt - REACTION_LOOKBACK_TICKS;
    }

    private void fail(long now) {
        outcome = Outcome.FAILED;
        if (attempts < MAX_ATTEMPTS) {
            phase = Phase.RETRY_PENDING;
            scheduledAt = now;
            notBefore = 0;
        } else {
            phase = Phase.FINISHED;
        }
    }

    private void markUnknown(long now) {
        phase = Phase.OUTCOME_UNKNOWN;
        outcome = Outcome.UNKNOWN;
        unknownSince = now;
    }

    private void finish(Outcome result) {
        phase = Phase.FINISHED;
        outcome = result;
    }
}
