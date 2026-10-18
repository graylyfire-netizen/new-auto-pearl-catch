package me.alpha432.oyvey.util.interaction;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Performs one "use an item on a pearl" attempt at a time, and only in ways a normal client could.
 *
 * <p>It has no Minecraft imports; everything the game does goes through {@link Host}. That keeps the rules below
 * testable without the game, and means this class never sends a packet by any route other than {@link Host}.
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li><b>Ownership</b> is asked of the host before the slot selection and again immediately before the use.</li>
 *   <li><b>Slot selection is normal and visible, and at most one slot change happens per tick.</b> If the item is already in the selected slot or the off hand, no
 *       slot packet is sent. Otherwise the hotbar slot is selected once (the host updates the client's selected slot
 *       and tells the server in the same call, so the two never disagree) and the item is used. The previous slot is
 *       restored once, on a <i>later</i> tick, and only if the player has not changed the slot in the meantime. There
 *       is never a swap-and-swap-back inside one tick.</li>
 *   <li><b>One attempt at a time.</b> While an attempt is pending (used, but the resulting wind charge has not been
 *       seen yet and the timeout has not passed) every further {@link #attempt} is refused without side effects.</li>
 *   <li><b>Retry limit.</b> At most {@link #MAX_ATTEMPTS_PER_PEARL} interactions per pearl, with at least
 *       {@link #FAILED_RETRY_TICKS} ticks between a failed attempt and the next one.</li>
 *   <li><b>No work for items that cannot be used.</b> An item on cooldown is detected before any slot change or
 *       packet.</li>
 *   <li><b>Cleanup.</b> {@link #reset()} (module disabled) and {@link #bindWorld} (level or player object changed)
 *       drop every piece of state without sending anything: a restore aimed at a world that no longer exists would
 *       be a stray packet.</li>
 * </ul>
 *
 * <p>Call {@link #tick} once per client tick in the phase before the movement packet is sent, then
 * {@link #attempt} if a target exists. Everything in this class happens inside that one phase.
 */
public final class ThrowSequencer {
    /** Hard cap on interactions per pearl, across all of its retries. */
    public static final int MAX_ATTEMPTS_PER_PEARL = 3;
    /** Minimum ticks between a failed attempt and the next one. */
    public static final int FAILED_RETRY_TICKS = 5;
    public static final int MIN_CONFIRM_TICKS = 6;
    public static final int MAX_CONFIRM_TICKS = 40;
    /** How long a deferred slot restore may wait (e.g. while a screen is open) before it is dropped. */
    public static final int LEASE_MAX_TICKS = 100;
    /** Per-pearl bookkeeping older than this (ticks since last use) is forgotten. */
    static final int TRACK_TTL_TICKS = 1200;

    public enum Hand {MAIN, OFF}

    /** Where the item is: the off hand, or a hotbar slot 0..8 of the main hand. */
    public record Source(Hand hand, int slot) {
        public static Source offhand() {
            return new Source(Hand.OFF, -1);
        }

        public static Source hotbar(int slot) {
            return new Source(Hand.MAIN, slot);
        }
    }

    /** Everything the sequencer needs from the game. All methods are called on the client thread, in the tick phase. */
    public interface Host {
        /** Live check, evaluated on every call: a living ender pearl whose owner is verifiably the local player. */
        boolean ownsPearl(int pearlId);

        /** Where an item can be used right now without any inventory transaction, or {@code null}. */
        Source locateCharge();

        /** The client's currently selected hotbar slot. */
        int selectedSlot();

        /**
         * Normal hotbar selection: set the client's selected slot to {@code slot} and tell the server immediately,
         * exactly one slot packet. Afterwards {@link #selectedSlot()} must equal {@code slot}.
         */
        void selectSlot(int slot);

        /** The item at {@code source} is on its use cooldown (the client would refuse it). */
        boolean isOnCooldown(Source source);

        /** One use-item interaction aimed at {@code yaw}/{@code pitch}; returns whether the client accepted it. */
        boolean use(Source source, float yaw, float pitch);

        /** Entity ids of the projectiles of this kind that the local player owns right now. */
        Set<Integer> ownChargeIds();

        /** A screen is open or the player is using an item: do not interrupt. */
        boolean playerBusy();
    }

    /**
     * @param handledTicks how long a pearl counts as dealt with after a confirmed throw
     * @param cooldownTicks ticks to wait after a throw before the next one
     * @param confirmTicks  how long to wait for the throw to show up before calling it failed (clamped)
     */
    public record Request(int pearlId, float yaw, float pitch, int handledTicks, int cooldownTicks, int confirmTicks) {
    }

    public enum Outcome {
        /** The use-item interaction was issued and is now pending confirmation. */
        SENT,
        /** The client refused the use; counted as an attempt. */
        REFUSED,
        /** An earlier attempt is still pending: nothing was done. */
        PENDING,
        COOLDOWN,
        /** The pearl was already dealt with, or has used up its attempts. */
        HANDLED,
        /** Ownership could not be verified at one of the two checks: nothing was used. */
        OWNERSHIP,
        NO_CHARGE,
        ITEM_COOLDOWN,
        /** The slot selection did not take effect; nothing was used. */
        SLOT_MISMATCH,
        /** The slot was already changed earlier this tick (a restore); never two slot changes in one tick. */
        SLOT_BUSY
    }

    private static final class Track {
        int attempts;
        long handledUntil = -1;
        long lastTouch;
    }

    private record Pending(int pearlId, Set<Integer> before, long deadline, int handledTicks) {
    }

    /** The slot we moved away from, and the one we selected. {@code holdUntil}: no restore up to this tick. */
    private static final class Lease {
        final int previousSlot;
        final int leasedSlot;
        final long createdTick;
        long holdUntil;

        Lease(int previousSlot, int leasedSlot, long createdTick) {
            this.previousSlot = previousSlot;
            this.leasedSlot = leasedSlot;
            this.createdTick = createdTick;
            this.holdUntil = createdTick;
        }
    }

    private final Map<Integer, Track> tracks = new HashMap<>();
    private long clock;
    private int cooldownLeft;
    private boolean coolingDown;
    private Pending pending;
    private Lease lease;
    /** Tick on which this class last changed the selected slot; at most one slot change per tick. */
    private long slotChangedTick = -1;
    private WeakReference<Object> level = new WeakReference<>(null);
    private WeakReference<Object> player = new WeakReference<>(null);

    // ---- lifecycle --------------------------------------------------------------------------------------------

    /**
     * Drops all state. Sends nothing, ever. Used when the module is enabled or disabled.
     *
     * <p>The clock and the "slot already changed this tick" marker are deliberately kept: a reset in the middle of
     * a tick must not make the sequencer forget that it already sent a slot packet on that tick.
     */
    public void reset() {
        tracks.clear();
        cooldownLeft = 0;
        coolingDown = false;
        pending = null;
        lease = null;
    }

    /**
     * Remembers which level and player object the state belongs to. If either differs from the last call (world
     * change, dimension change, respawn, reconnect) all state is dropped, without sending anything.
     *
     * @return whether state was dropped
     */
    public boolean bindWorld(Object currentLevel, Object currentPlayer) {
        if (level.get() == currentLevel && player.get() == currentPlayer) return false;
        reset();
        level = new WeakReference<>(currentLevel);
        player = new WeakReference<>(currentPlayer);
        return true;
    }

    // ---- per tick ---------------------------------------------------------------------------------------------

    /** Advances time, resolves a pending attempt and performs a deferred slot restore. Call once per tick, first. */
    public void tick(Host host) {
        clock++;
        coolingDown = cooldownLeft > 0;
        if (coolingDown) cooldownLeft--;
        tracks.values().removeIf(t -> t.lastTouch + TRACK_TTL_TICKS < clock);

        if (pending != null) resolvePending(host);
        if (lease != null && pending == null) restoreSlot(host);
    }

    /** Whether {@link #attempt} could currently do anything (no pending attempt, no cooldown running). */
    public boolean canAttempt() {
        return pending == null && !coolingDown;
    }

    public boolean isHandled(int pearlId) {
        Track t = tracks.get(pearlId);
        return t != null && (t.attempts >= MAX_ATTEMPTS_PER_PEARL || t.handledUntil >= clock);
    }

    public Outcome attempt(Host host, Request req) {
        if (pending != null) return Outcome.PENDING;
        if (coolingDown) return Outcome.COOLDOWN;
        if (isHandled(req.pearlId())) return Outcome.HANDLED;

        // ownership check 1: before any interaction at all
        if (!host.ownsPearl(req.pearlId())) return Outcome.OWNERSHIP;

        Source source = host.locateCharge();
        if (source == null) return Outcome.NO_CHARGE;
        if (source.hand() == Hand.MAIN && (source.slot() < 0 || source.slot() > 8)) return Outcome.NO_CHARGE;
        // an item the client would refuse must not cost a slot change or a packet
        if (host.isOnCooldown(source)) return Outcome.ITEM_COOLDOWN;

        if (source.hand() == Hand.MAIN && host.selectedSlot() != source.slot()) {
            if (slotChangedTick == clock) return Outcome.SLOT_BUSY; // try again next tick
            int previous = host.selectedSlot();
            host.selectSlot(source.slot());
            slotChangedTick = clock;
            if (host.selectedSlot() != source.slot()) return Outcome.SLOT_MISMATCH;
            if (lease == null) lease = new Lease(previous, source.slot(), clock);
        }

        // ownership check 2: immediately before the use itself
        if (!host.ownsPearl(req.pearlId())) return Outcome.OWNERSHIP;

        Track track = tracks.computeIfAbsent(req.pearlId(), id -> new Track());
        track.attempts++;
        track.lastTouch = clock;
        Set<Integer> before = host.ownChargeIds();

        if (!host.use(source, req.yaw(), req.pitch())) {
            cooldownLeft = Math.max(cooldownLeft, FAILED_RETRY_TICKS);
            holdSlotForRetry(track);
            return Outcome.REFUSED;
        }

        int confirm = Math.max(MIN_CONFIRM_TICKS, Math.min(MAX_CONFIRM_TICKS, req.confirmTicks()));
        pending = new Pending(req.pearlId(), before, clock + confirm, req.handledTicks());
        cooldownLeft = Math.max(cooldownLeft, req.cooldownTicks());
        return Outcome.SENT;
    }

    // ---- inspection (tests, debug output) ---------------------------------------------------------------------

    public boolean isPending() {
        return pending != null;
    }

    public boolean hasSlotLease() {
        return lease != null;
    }

    public int attemptsFor(int pearlId) {
        Track t = tracks.get(pearlId);
        return t == null ? 0 : t.attempts;
    }

    public int trackedPearls() {
        return tracks.size();
    }

    public long clock() {
        return clock;
    }

    // ---- internals --------------------------------------------------------------------------------------------

    private void resolvePending(Host host) {
        Pending p = pending;
        boolean seen = false;
        for (int id : host.ownChargeIds()) {
            if (!p.before().contains(id)) {
                seen = true;
                break;
            }
        }
        if (seen) {
            Track t = tracks.computeIfAbsent(p.pearlId(), id -> new Track());
            t.handledUntil = clock + p.handledTicks();
            t.lastTouch = clock;
            pending = null;
        } else if (clock >= p.deadline()) {
            // never saw it: the attempt stays counted; a retry (if any are left) waits a little
            cooldownLeft = Math.max(cooldownLeft, FAILED_RETRY_TICKS);
            holdSlotForRetry(tracks.get(p.pearlId()));
            pending = null;
        }
    }

    /**
     * After a failed attempt that may be retried, stay on the item's slot until the retry window has passed instead
     * of switching back and then forth again.
     */
    private void holdSlotForRetry(Track track) {
        if (lease != null && track != null && track.attempts < MAX_ATTEMPTS_PER_PEARL) {
            lease.holdUntil = clock + FAILED_RETRY_TICKS + 1;
        }
    }

    /** Only ever called from {@link #tick}, which runs before {@link #attempt} on every tick: so a lease is never
     *  restored on the tick it was created. */
    private void restoreSlot(Host host) {
        Lease l = lease;
        if (host.selectedSlot() != l.leasedSlot) { // the player (or the server) moved it: leave it alone
            lease = null;
            return;
        }
        if (clock - l.createdTick > LEASE_MAX_TICKS) {
            lease = null;
            return;
        }
        if (clock <= l.holdUntil) return; // a retry may still come
        if (host.playerBusy()) return; // try again when they are done
        host.selectSlot(l.previousSlot);
        slotChangedTick = clock;
        lease = null;
    }
}
