package me.alpha432.oyvey.features.modules.combat;

import com.google.gson.JsonElement;
import me.alpha432.oyvey.OyVey;
import me.alpha432.oyvey.features.modules.Module;
import me.alpha432.oyvey.features.settings.Setting;
import me.alpha432.oyvey.util.inventory.InventoryUtil;
import me.alpha432.oyvey.util.inventory.Result;
import me.alpha432.oyvey.util.prediction.CatchTrack;
import me.alpha432.oyvey.util.prediction.CatchTrack.Phase;
import me.alpha432.oyvey.util.prediction.InterceptSolver;
import me.alpha432.oyvey.util.prediction.InterceptSolver.Burst;
import me.alpha432.oyvey.util.prediction.InterceptSolver.Charge;
import me.alpha432.oyvey.util.prediction.InterceptSolver.Pick;
import me.alpha432.oyvey.util.prediction.InterceptSolver.Request;
import me.alpha432.oyvey.util.prediction.InterceptSolver.Solution;
import me.alpha432.oyvey.util.prediction.InterceptSolver.V3;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static me.alpha432.oyvey.util.inventory.InventoryUtil.FULL_SCOPE;
import static me.alpha432.oyvey.util.inventory.InventoryUtil.HOTBAR_SCOPE;

/**
 * Throws a wind charge so that it meets one of the local player's own ender pearls while it is in flight.
 *
 * <p>Only pearls whose owner is positively the local player are ever tracked; enemy pearls and pearls whose owner
 * the client cannot resolve never enter the pipeline. Each tracked pearl has a {@link CatchTrack} that enforces
 * one attempt plus at most one retry and classifies each attempt from observed evidence (our charge spawned, it
 * burst near the pearl, the pearl's velocity left free flight) instead of assuming that a sent use succeeded.
 *
 * <p>All maths lives in {@link InterceptSolver}; this class only reads game state, calls the solver and performs
 * the throw. The throw uses the base's existing rotation pattern: set the player's rotation, use the item (the
 * use-item packet carries the current yaw/pitch), then put the rotation back, all inside one client tick so the
 * camera never visibly turns.
 */
public class PearlCatchModule extends Module {
    /** Vanilla: wind charge burst radius used for the knockback falloff. */
    private static final float DEFAULT_BURST_RADIUS = 1.2f;
    /** A velocity change from free flight larger than this (blocks per tick) counts as the pearl being pushed. */
    private static final double REACTION_THRESHOLD = 0.1;
    /** Slack on top of the burst's reach when deciding whether a burst was close enough to affect the pearl. */
    private static final double NEAR_SLACK = 0.5;
    /** A freshly spawned charge is only attributed to a throw if it appears this close to where we threw from. */
    private static final double CLAIM_DISTANCE = 5.0;

    private final Setting<Float> range = num("Range", 48.0f, 8.0f, 96.0f);
    private final Setting<Integer> tickDelay = num("Tick Delay", CatchTrack.DEFAULT_TICK_DELAY,
            CatchTrack.MIN_TICK_DELAY, CatchTrack.MAX_TICK_DELAY);
    private final Setting<Integer> window = num("Window", 24, 4, 60);
    private final Setting<Float> hitRadius = num("HitRadius", 0.55f, 0.2f, 1.5f);
    private final Setting<Pick> pick = mode("Pick", Pick.CLOSEST);
    private final Setting<Boolean> autoLatency = bool("AutoLatency", true);
    private final Setting<Integer> extraTicks = num("ExtraTicks", 1, 0, 10);
    private final Setting<Momentum> momentumSource = mode("Momentum", Momentum.DISPLACEMENT);
    private final Setting<Boolean> windBursts = bool("WindBursts", true);
    private final Setting<Float> burstBoost = num("BurstBoost", 1.22f, 0.0f, 4.0f);
    private final Setting<Float> burstRadius = num("BurstRadius", DEFAULT_BURST_RADIUS, 0.5f, 3.0f);
    private final Setting<Boolean> lineOfSight = bool("LineOfSight", true);
    private final Setting<Integer> cooldown = num("Cooldown", 10, 0, 40);
    private final Setting<Boolean> inventory = bool("Inventory", false);
    private final Setting<Boolean> silent = bool("Silent", true);
    private final Setting<Boolean> debug = bool("Debug", false);

    /** Per-pearl state, keyed by entity id. Only pearls verified as ours are ever added. */
    private final Map<Integer, Track> tracks = new HashMap<>();
    private Object trackedLevel;
    private long clock;
    private int cooldownLeft;
    private String lastInfo;
    private List<Charge> inFlightCache = List.of();
    private long inFlightClock = -1;

    /** Everything the module remembers about one pearl; the attempt rules themselves live in {@link CatchTrack}. */
    private static final class Track {
        final int id;
        final UUID uuid;
        final CatchTrack core = new CatchTrack();
        /** Where we threw from and which of our charges already existed, to recognise the charge this throw makes. */
        Vec3 throwOrigin;
        long executedClock;
        Set<Integer> chargesBefore = Set.of();
        int chargeId = -1;
        Vec3 chargePos = Vec3.ZERO;
        Vec3 chargeVel = Vec3.ZERO;
        /** The pearl's velocity on the previous tick, to tell a push from free flight. */
        Vec3 lastVel;

        Track(Entity pearl) {
            this.id = pearl.getId();
            this.uuid = pearl.getUUID();
        }
    }

    /** Where the shooter's launch momentum comes from; see {@link #shooterMomentum()}. */
    public enum Momentum {
        /** Position minus previous-tick position: what the server sees (default). */
        DISPLACEMENT,
        /** The client's friction-scaled velocity. Kept only so the two can be compared in game. */
        VELOCITY
    }

    public PearlCatchModule() {
        super("PearlCatch", "Throws a wind charge to intercept ender pearls in flight.", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        clearTracking();
    }

    @Override
    public void onDisable() {
        clearTracking();
    }

    private void clearTracking() {
        tracks.clear();
        trackedLevel = null;
        cooldownLeft = 0;
        lastInfo = null;
        inFlightCache = List.of();
        inFlightClock = -1;
    }

    /** A saved Tick Delay that is not a whole number from 1 to 10 falls back to the default instead of clamping. */
    @Override
    public void fromJson(JsonElement element) {
        super.fromJson(element);
        if (element == null || !element.isJsonObject()) return;
        JsonElement saved = element.getAsJsonObject().get(tickDelay.getName());
        if (saved == null || saved.isJsonNull()) return;
        String raw;
        try {
            raw = saved.getAsString();
        } catch (RuntimeException e) {
            raw = null;
        }
        tickDelay.setValueNoEvent(CatchTrack.parseTickDelay(raw));
    }

    /** The configured scheduling delay in client ticks, always within 1..10. */
    private int delay() {
        return CatchTrack.sanitizeTickDelay(tickDelay.getValue());
    }

    @Override
    public String getDisplayInfo() {
        return lastInfo;
    }

    @Override
    public void onTick() {
        if (nullCheck() || mc.gameMode == null) {
            clearTracking();
            return;
        }
        if (mc.level != trackedLevel) {          // joined a world, changed dimension, reconnected
            clearTracking();
            trackedLevel = mc.level;
        }

        clock++;
        if (cooldownLeft > 0) cooldownLeft--;

        discoverPearls();
        advanceTracks();
        executeDue();
    }

    // ------------------------------------------------------------------ eligibility

    /** True only if the client can positively resolve the projectile's owner and it is the local player. */
    private boolean isOwnedByLocalPlayer(Entity entity) {
        if (!(entity instanceof Projectile projectile)) return false;
        Entity owner = projectile.getOwner();
        return owner != null && owner.getUUID().equals(mc.player.getUUID());
    }

    /** One range definition for the whole pipeline: distance from the pearl to the player's bounding box. */
    private boolean inRange(Entity pearl) {
        double r = range.getValue();
        return distanceSqToPlayerBox(pearl.position()) <= r * r;
    }

    private double distanceSqToPlayerBox(Vec3 p) {
        AABB box = mc.player.getBoundingBox();
        double dx = Math.max(Math.max(box.minX - p.x, 0.0), p.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - p.y, 0.0), p.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - p.z, 0.0), p.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    /** Everything that must hold, now, for a pearl to be scheduled or thrown at. */
    private boolean eligible(Entity pearl) {
        return pearl != null && pearl.getType() == EntityType.ENDER_PEARL && pearl.isAlive()
                && isOwnedByLocalPlayer(pearl)
                && inRange(pearl)
                // a pearl that has effectively stopped is not going to be intercepted mid-air
                && pearl.getDeltaMovement().lengthSqr() > 1.0E-3;
    }

    // ------------------------------------------------------------------ tracking

    /** Starts tracking our own pearls as they come within range. Other pearls never get a Track. */
    private void discoverPearls() {
        List<Entity> found = mc.level.getEntities((Entity) null,
                mc.player.getBoundingBox().inflate(range.getValue()),
                e -> e.getType() == EntityType.ENDER_PEARL && e.isAlive() && isOwnedByLocalPlayer(e));
        for (Entity pearl : found) {
            if (tracks.containsKey(pearl.getId()) || !inRange(pearl)) continue;
            tracks.put(pearl.getId(), new Track(pearl));
            if (debug.getValue()) OyVey.LOGGER.info("[PearlCatch] tracking own pearl #{}", pearl.getId());
        }
    }

    /** Per-tick bookkeeping for every tracked pearl: evidence, timeouts, scheduling. Never throws a charge. */
    private void advanceTracks() {
        Iterator<Track> it = tracks.values().iterator();
        while (it.hasNext()) {
            Track t = it.next();
            Entity pearl = mc.level.getEntity(t.id);
            if (pearl == null || pearl.isRemoved() || !pearl.getUUID().equals(t.uuid)) {
                t.core.onPearlGone(clock);
                logOutcome(t, "pearl gone");
                it.remove();
                continue;
            }
            if (!isOwnedByLocalPlayer(pearl)) {      // ownership no longer verifiable: stop touching it
                t.core.cancel();
                it.remove();
                continue;
            }

            Phase before = t.core.phase();
            observePearlVelocity(t, pearl);
            observeCharge(t, pearl);

            switch (t.core.phase()) {
                case TRACKING -> {
                    if (eligible(pearl) && hasCharge() && plan(pearl).isPresent()) {
                        t.core.schedule(clock);
                    }
                }
                case ATTEMPT_PENDING, RETRY_PENDING -> {
                    if (!eligible(pearl)) t.core.invalidatePending();
                    t.core.tick(clock);
                }
                case AWAITING_CONFIRMATION, OUTCOME_UNKNOWN -> t.core.tick(clock);
                case FINISHED -> {
                }
            }

            if (t.core.phase() != before && debug.getValue()) {
                OyVey.LOGGER.info("[PearlCatch] pearl #{} {} -> {} (attempts {}, outcome {})", t.id, before,
                        t.core.phase(), t.core.attempts(), t.core.outcome());
            }
            if (before != t.core.phase() && t.core.isFinished()) logOutcome(t, "finished");
        }
    }

    /**
     * Compares the pearl's velocity with what free flight would give from last tick's velocity. A jump bigger than
     * {@link #REACTION_THRESHOLD} means something pushed it. Ordinary entity-order jitter changes the result by about
     * one gravity-and-drag step (~0.05), which stays below the threshold.
     */
    private void observePearlVelocity(Track t, Entity pearl) {
        Vec3 v = pearl.getDeltaMovement();
        if (t.lastVel != null) {
            double ex = t.lastVel.x * InterceptSolver.PEARL_DRAG;
            double ey = (t.lastVel.y - InterceptSolver.PEARL_GRAVITY) * InterceptSolver.PEARL_DRAG;
            double ez = t.lastVel.z * InterceptSolver.PEARL_DRAG;
            double dx = v.x - ex, dy = v.y - ey, dz = v.z - ez;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) > REACTION_THRESHOLD) t.core.onReaction(clock);
        }
        t.lastVel = v;
    }

    /** Feeds the lifecycle the evidence about the charge from this track's last throw. */
    private void observeCharge(Track t, Entity pearl) {
        if (!t.core.isWaiting()) return;

        if (t.chargeId < 0) {
            // only the oldest throw that has not found its charge may claim a new one
            if (!isOldestUnclaimed(t)) return;
            for (Entity e : ownChargesNear(t.throwOrigin)) {
                int id = e.getId();
                if (t.chargesBefore.contains(id) || isClaimed(id)) continue;
                t.chargeId = id;
                t.chargePos = e.position();
                t.chargeVel = e.getDeltaMovement();
                t.core.onChargeSeen(clock);
                return;
            }
            return;
        }
        if (t.core.chargeGone()) return;

        Entity charge = mc.level.getEntity(t.chargeId);
        if (charge != null && !charge.isRemoved()) {
            t.chargePos = charge.position();
            t.chargeVel = charge.getDeltaMovement();
            return;
        }
        // the charge is gone: it burst where it would have been this tick
        Vec3 burst = t.chargePos.add(t.chargeVel);
        double reach = burstRadius.getValue() * 2.0 + NEAR_SLACK;
        t.core.onChargeGone(clock, burst.distanceToSqr(pearl.position()) <= reach * reach);
    }

    private boolean isClaimed(int chargeId) {
        for (Track other : tracks.values()) {
            if (other.chargeId == chargeId) return true;
        }
        return false;
    }

    private boolean isOldestUnclaimed(Track candidate) {
        for (Track other : tracks.values()) {
            if (other != candidate && other.core.isWaiting() && other.chargeId < 0
                    && other.executedClock < candidate.executedClock) {
                return false;
            }
        }
        return true;
    }

    private List<Entity> ownChargesNear(Vec3 center) {
        AABB box = new AABB(center.x - CLAIM_DISTANCE, center.y - CLAIM_DISTANCE, center.z - CLAIM_DISTANCE,
                center.x + CLAIM_DISTANCE, center.y + CLAIM_DISTANCE, center.z + CLAIM_DISTANCE);
        return mc.level.getEntities((Entity) null, box,
                e -> e.getType() == EntityType.WIND_CHARGE && e.isAlive() && isOwnedByLocalPlayer(e));
    }

    // ------------------------------------------------------------------ execution

    /**
     * Executes at most one throw per tick: the most imminent intercept among the attempts whose Tick Delay has
     * elapsed. Everything that was true when the attempt was scheduled is checked again, and the aim is recomputed
     * from current positions, so the delay never means acting on stale data.
     */
    private void executeDue() {
        if (cooldownLeft > 0 || mc.screen != null || mc.player.isUsingItem()) return;

        int delay = delay();
        Track bestTrack = null;
        Solution best = null;
        int lead = 0;
        boolean leadKnown = false;
        for (Track t : tracks.values()) {
            if (!t.core.isDue(clock, delay)) continue;
            Entity pearl = mc.level.getEntity(t.id);
            if (!eligible(pearl) || !pearl.getUUID().equals(t.uuid)) {
                t.core.invalidatePending();
                continue;
            }
            if (!leadKnown) {
                lead = extraTicks.getValue() + (autoLatency.getValue() ? pingTicks() : 0);
                leadKnown = true;
            }
            Optional<Solution> solution = solveFor(pearl, inFlight(), lead);
            if (solution.isEmpty()) {
                t.core.invalidatePending();
                continue;
            }
            // most imminent intercept first
            if (best == null || solution.get().arrivalTick() < best.arrivalTick()) {
                best = solution.get();
                bestTrack = t;
            }
        }
        if (best == null) return;

        Result charge = InventoryUtil.find(Items.WIND_CHARGE, inventory.getValue() ? FULL_SCOPE : HOTBAR_SCOPE);
        if (!charge.found()) return;                     // stays pending; nothing was thrown

        Set<Integer> before = new HashSet<>();
        Vec3 origin = mc.player.getEyePosition();
        for (Entity e : ownChargesNear(origin)) before.add(e.getId());

        if (!fire(charge, best)) {
            // Not thrown (swap failed, or the item is on its own cooldown): no attempt is consumed.
            bestTrack.core.onRefused(clock);
            return;
        }
        bestTrack.throwOrigin = origin;
        bestTrack.executedClock = clock;
        bestTrack.chargesBefore = before;
        bestTrack.chargeId = -1;
        bestTrack.core.onExecuted(clock, pingTicks(), best.flightTicks());
        cooldownLeft = cooldown.getValue();
        lastInfo = "t+" + best.arrivalTick();

        if (debug.getValue()) {
            OyVey.LOGGER.info("[PearlCatch] pearl #{} attempt {}/{} -> yaw {} pitch {} | arrive t+{} (flight {}t, contact {}t) miss {} blocks | lead {}t | delay {}t | bursts {}",
                    bestTrack.id, bestTrack.core.attempts(), CatchTrack.MAX_ATTEMPTS, fmt(best.yaw()), fmt(best.pitch()),
                    best.arrivalTick(), best.flightTicks(), fmt(best.contactTime()), fmt(best.miss()), lead, delay,
                    inFlight().size());
        }
    }

    private boolean hasCharge() {
        return InventoryUtil.find(Items.WIND_CHARGE, inventory.getValue() ? FULL_SCOPE : HOTBAR_SCOPE).found();
    }

    /** Can a throw at this pearl be aimed right now? Same computation as at execution time. */
    private Optional<Solution> plan(Entity pearl) {
        int lead = extraTicks.getValue() + (autoLatency.getValue() ? pingTicks() : 0);
        return solveFor(pearl, inFlight(), lead);
    }

    /** Wind charges currently in flight, collected once per tick. */
    private List<Charge> inFlight() {
        if (!windBursts.getValue()) return List.of();
        if (inFlightClock != clock) {
            inFlightCache = collectCharges();
            inFlightClock = clock;
        }
        return inFlightCache;
    }

    private void logOutcome(Track t, String why) {
        if (t.core.outcome() != CatchTrack.Outcome.NONE) {
            lastInfo = switch (t.core.outcome()) {
                case SUCCESS -> "caught";
                case FAILED -> "missed";
                default -> "unknown";
            };
        }
        if (debug.getValue()) {
            OyVey.LOGGER.info("[PearlCatch] pearl #{} {}: outcome {} after {} attempt(s)", t.id, why,
                    t.core.outcome(), t.core.attempts());
        }
    }

    private Optional<Solution> solveFor(Entity pearl, List<Charge> inFlight, int lead) {
        V3 pearlPos = v3(pearl.position());
        V3 pearlVel = v3(pearl.getDeltaMovement());
        int win = window.getValue();

        List<Burst> bursts = inFlight.isEmpty()
                ? List.of()
                : InterceptSolver.deriveBursts(pearlPos, pearlVel, inFlight, win, hitRadius.getValue(),
                burstRadius.getValue(), burstBoost.getValue());

        int validTicks = pearlFlightTicks(pearl, pearlPos, pearlVel, bursts, win);

        InterceptSolver.Clearance clearance = lineOfSight.getValue()
                ? (from, to) -> mc.level.clip(new ClipContext(vec(from), vec(to),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player)).getType() == HitResult.Type.MISS
                : InterceptSolver.Clearance.ALWAYS;

        Request request = new Request(
                pearlPos, pearlVel,
                v3(mc.player.getEyePosition()), v3(shooterMomentum()), mc.player.onGround(),
                lead, win, hitRadius.getValue(), pick.getValue(), bursts, clearance, validTicks);
        return InterceptSolver.solve(request);
    }

    /**
     * The movement the server will add to the charge's launch velocity: the shooter's displacement over the last
     * tick (the use-item packet follows this tick's movement packet). {@code getDeltaMovement()} is not that value:
     * it has already been scaled by friction for the next tick (about 0.55x on the ground, 0.91x in the air
     * horizontally), so using it under-compensates exactly when the player is moving.
     */
    private Vec3 shooterMomentum() {
        if (momentumSource.getValue() == Momentum.VELOCITY) return mc.player.getDeltaMovement();
        return new Vec3(mc.player.getX() - mc.player.xo, mc.player.getY() - mc.player.yo,
                mc.player.getZ() - mc.player.zo);
    }

    /**
     * Last predicted tick at which the pearl is still flying, i.e. the tick before the predicted path first meets a
     * block. The solver treats the pearl as free flight, so without this it could aim at a point the pearl never
     * reaches.
     */
    private int pearlFlightTicks(Entity pearl, V3 pos, V3 vel, List<Burst> bursts, int ticks) {
        V3[] path = InterceptSolver.predictPearl(pos, vel, ticks, bursts);
        for (int i = 1; i <= ticks; i++) {
            if (mc.level.clip(new ClipContext(vec(path[i - 1]), vec(path[i]),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, pearl)).getType() != HitResult.Type.MISS) {
                return i - 1;
            }
        }
        return ticks;
    }

    private List<Charge> collectCharges() {
        List<Charge> out = new ArrayList<>();
        List<Entity> charges = mc.level.getEntities((Entity) null,
                mc.player.getBoundingBox().inflate(range.getValue()),
                e -> e.getType() == EntityType.WIND_CHARGE && e.isAlive());
        for (Entity e : charges) {
            out.add(new Charge(v3(e.position()), v3(e.getDeltaMovement())));
        }
        return out;
    }

    /**
     * Silent throw: rotate, use, rotate back, all within this tick.
     *
     * @return whether the client accepted the use (not whether the server will); false if the swap failed or the
     * item was refused, e.g. because the wind charge is still on its own cooldown
     */
    private boolean fire(Result charge, Solution solution) {
        float prevYaw = mc.player.getYRot();
        float prevPitch = mc.player.getXRot();
        float prevHead = mc.player.yHeadRot;

        boolean[] accepted = {false};
        OyVey.rotationManager.setPlayerRotations(solution.yaw(), solution.pitch());
        InventoryUtil.withSwap(charge, () -> {
            InteractionResult result = mc.gameMode.useItem(mc.player, charge.hand());
            accepted[0] = result.consumesAction();
        });
        if (silent.getValue() || !accepted[0]) {
            mc.player.setYRot(prevYaw);
            mc.player.setXRot(prevPitch);
            mc.player.yHeadRot = prevHead;
        }
        return accepted[0];
    }

    /** Round-trip latency in whole ticks (50 ms each). */
    private int pingTicks() {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return 0;
        PlayerInfo info = connection.getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : Math.max(0, Math.round(info.getLatency() / 50.0f));
    }

    private static V3 v3(Vec3 v) {
        return new V3(v.x, v.y, v.z);
    }

    private static Vec3 vec(V3 v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }
}
