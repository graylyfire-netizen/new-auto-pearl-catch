package me.alpha432.oyvey.features.modules.combat;

import me.alpha432.oyvey.OyVey;
import me.alpha432.oyvey.features.modules.Module;
import me.alpha432.oyvey.features.settings.Setting;
import me.alpha432.oyvey.util.interaction.PearlOwnership;
import me.alpha432.oyvey.util.interaction.PearlOwnership.Verdict;
import me.alpha432.oyvey.util.interaction.ThrowSequencer;
import me.alpha432.oyvey.util.interaction.ThrowSequencer.Outcome;
import me.alpha432.oyvey.util.interaction.ThrowSequencer.Source;
import me.alpha432.oyvey.util.inventory.InventoryUtil;
import me.alpha432.oyvey.util.prediction.InterceptSolver;
import me.alpha432.oyvey.util.prediction.InterceptSolver.Burst;
import me.alpha432.oyvey.util.prediction.InterceptSolver.Charge;
import me.alpha432.oyvey.util.prediction.InterceptSolver.Pick;
import me.alpha432.oyvey.util.prediction.InterceptSolver.Request;
import me.alpha432.oyvey.util.prediction.InterceptSolver.Solution;
import me.alpha432.oyvey.util.prediction.InterceptSolver.V3;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Throws a wind charge so that it meets an ender pearl that is already in flight.
 *
 * <p>All maths lives in {@link InterceptSolver}; this class only reads game state, calls the solver and hands the
 * throw to a {@link ThrowSequencer}. Only ender pearls that the local player verifiably threw are considered.
 *
 * <p>The throw happens in {@link #onPreTick()}, i.e. before the client tick's movement packet, which is where a
 * vanilla client sends its slot-change and use-item packets. The wind charge is selected like a player would
 * (one visible hotbar selection, or none if it is already held or in the off hand), used, and the previous slot is
 * restored on a later tick. The use-item packet carries the aimed yaw/pitch; with {@code Silent} the client's own
 * rotation is put back right afterwards, inside the same tick, so the camera never visibly turns.
 */
public class PearlCatchModule extends Module {
    /** Vanilla: wind charge burst radius used for the knockback falloff. */
    private static final float DEFAULT_BURST_RADIUS = 1.2f;
    /** Ticks added to the round-trip time when waiting for the thrown charge to show up. */
    private static final int CONFIRM_MARGIN_TICKS = 4;

    private final Setting<Float> range = num("Range", 48.0f, 8.0f, 96.0f);
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
    /**
     * Kept so existing configs and the GUI layout still load, but no longer used: moving a charge out of the main
     * inventory meant two container-click packets with no inventory open, in the same tick as the throw.
     */
    private final Setting<Boolean> inventory = register(new Setting<>("Inventory", false,
            "Deprecated, ignored. Wind charges must be in the hotbar or off hand."));
    private final Setting<Boolean> silent = bool("Silent", true);
    private final Setting<Boolean> debug = bool("Debug", false);

    private final ThrowSequencer sequencer = new ThrowSequencer();
    private final ThrowSequencer.Host host = new ClientHost();
    private String lastInfo;

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
        sequencer.reset();
        lastInfo = null;
    }

    /** Drops every pending attempt and slot restore. Nothing is sent: the player simply stays where they are. */
    @Override
    public void onDisable() {
        sequencer.reset();
        lastInfo = null;
    }

    @Override
    public String getDisplayInfo() {
        return lastInfo;
    }

    /**
     * Runs before this tick's movement packet. Everything that talks to the server happens here, in the order a
     * vanilla client uses: slot change, use item, then (later in the tick) the movement packet.
     */
    @Override
    public void onPreTick() {
        if (nullCheck() || mc.gameMode == null) return;

        // a different level or player object (world change, dimension change, respawn, reconnect): forget everything
        if (sequencer.bindWorld(mc.level, mc.player)) lastInfo = null;
        sequencer.tick(host);

        if (mc.player.isSpectator() || host.playerBusy()) return;
        if (!sequencer.canAttempt()) return; // an attempt is pending, or the cooldown is running
        if (host.locateCharge() == null) return;

        List<Entity> pearls = mc.level.getEntities((Entity) null,
                mc.player.getBoundingBox().inflate(range.getValue()), this::isTargetPearl);
        if (pearls.isEmpty()) return;

        List<Charge> inFlight = windBursts.getValue() ? collectCharges() : List.of();
        int ping = pingTicks();
        int lead = extraTicks.getValue() + (autoLatency.getValue() ? ping : 0);

        Entity bestPearl = null;
        Solution best = null;
        for (Entity pearl : pearls) {
            Optional<Solution> solution = solveFor(pearl, inFlight, lead);
            if (solution.isEmpty()) continue;
            // most imminent intercept first
            if (best == null || solution.get().arrivalTick() < best.arrivalTick()) {
                best = solution.get();
                bestPearl = pearl;
            }
        }
        if (best == null) return;

        Outcome outcome = sequencer.attempt(host, new ThrowSequencer.Request(bestPearl.getId(), best.yaw(), best.pitch(),
                window.getValue(), cooldown.getValue(), ping + CONFIRM_MARGIN_TICKS));
        if (outcome != Outcome.SENT) {
            // nothing was thrown (or the client refused it); the sequencer decides about retries, so just report
            if (debug.getValue()) OyVey.LOGGER.info("[PearlCatch] pearl #{} not thrown: {}", bestPearl.getId(), outcome);
            return;
        }
        lastInfo = "t+" + best.arrivalTick();

        if (debug.getValue()) {
            OyVey.LOGGER.info("[PearlCatch] pearl #{} -> yaw {} pitch {} | arrive t+{} (flight {}t, contact {}t) miss {} blocks | lead {}t | bursts {}",
                    bestPearl.getId(), fmt(best.yaw()), fmt(best.pitch()), best.arrivalTick(), best.flightTicks(),
                    fmt(best.contactTime()), fmt(best.miss()), lead, inFlight.size());
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
     * tick. The throw is sent before this tick's movement packet, so the server still has the position and movement
     * of the <i>previous</i> movement packet, which is exactly the client's state at the start of the tick
     * ({@code getX() - xo} is that previous packet's displacement). {@code getDeltaMovement()} is not that value:
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

    private boolean isTargetPearl(Entity entity) {
        // an ender pearl, still alive, whose resolved owner is verifiably the local player; everything else
        // (other players' pearls, pearls whose owner is not resolved) is ignored
        if (!isOwnPearl(entity)) return false;
        if (sequencer.isHandled(entity.getId())) return false;
        // a pearl that has effectively stopped is not going to be intercepted mid-air
        return entity.getDeltaMovement().lengthSqr() > 1.0E-3;
    }

    /** Evaluated afresh on every call; the sequencer asks again right before each interaction. */
    private boolean isOwnPearl(Entity entity) {
        return classify(entity).isLocal();
    }

    private Verdict classify(Entity entity) {
        UUID ownerUuid = null;
        boolean ownerIsLocalPlayer = false;
        if (entity instanceof Projectile projectile) {
            Entity owner = projectile.getOwner();
            if (owner != null) {
                ownerUuid = owner.getUUID();
                ownerIsLocalPlayer = owner == mc.player;
            }
        }
        return PearlOwnership.classify(new PearlOwnership.Snapshot(entity.getType() == EntityType.ENDER_PEARL,
                entity.isAlive(), ownerUuid, ownerIsLocalPlayer), mc.player.getUUID());
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

    /** The game side of {@link ThrowSequencer}: every method reads live state and runs on the client thread. */
    private final class ClientHost implements ThrowSequencer.Host {
        @Override
        public boolean ownsPearl(int pearlId) {
            if (nullCheck()) return false;
            Entity entity = mc.level.getEntity(pearlId);
            return entity != null && isOwnPearl(entity);
        }

        /** Prefers what is already in hand, so no slot change is needed whenever it can be avoided. */
        @Override
        public Source locateCharge() {
            int selected = InventoryUtil.selected();
            if (mc.player.getInventory().getItem(selected).is(Items.WIND_CHARGE)) return Source.hotbar(selected);
            if (mc.player.getOffhandItem().is(Items.WIND_CHARGE)) return Source.offhand();
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getItem(i).is(Items.WIND_CHARGE)) return Source.hotbar(i);
            }
            return null;
        }

        @Override
        public int selectedSlot() {
            return InventoryUtil.selected();
        }

        /** Selects the slot and sends the slot packet right away (setSelectedSlot + ensureHasSentCarriedItem). */
        @Override
        public void selectSlot(int slot) {
            InventoryUtil.swap(slot);
        }

        @Override
        public boolean isOnCooldown(Source source) {
            return mc.player.getCooldowns().isOnCooldown(stackAt(source));
        }

        /**
         * Rotate, use, and (with Silent, or if refused) put the rotation back, all inside this tick. The use-item
         * packet carries the aimed yaw/pitch. No slot change happens here: the sequencer did that, once, before.
         */
        @Override
        public boolean use(Source source, float yaw, float pitch) {
            InteractionHand hand = source.hand() == ThrowSequencer.Hand.OFF ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            float prevYaw = mc.player.getYRot();
            float prevPitch = mc.player.getXRot();
            float prevHead = mc.player.yHeadRot;

            OyVey.rotationManager.setPlayerRotations(yaw, pitch);
            InteractionResult result = mc.gameMode.useItem(mc.player, hand);
            boolean accepted = result.consumesAction();
            if (silent.getValue() || !accepted) {
                mc.player.setYRot(prevYaw);
                mc.player.setXRot(prevPitch);
                mc.player.yHeadRot = prevHead;
            }
            return accepted;
        }

        @Override
        public Set<Integer> ownChargeIds() {
            Set<Integer> ids = new HashSet<>();
            List<Entity> charges = mc.level.getEntities((Entity) null,
                    mc.player.getBoundingBox().inflate(range.getValue()),
                    e -> e.getType() == EntityType.WIND_CHARGE && e.isAlive()
                            && e instanceof Projectile projectile && projectile.getOwner() == mc.player);
            for (Entity charge : charges) ids.add(charge.getId());
            return ids;
        }

        @Override
        public boolean playerBusy() {
            return mc.screen != null || mc.player.isUsingItem();
        }

        private ItemStack stackAt(Source source) {
            return source.hand() == ThrowSequencer.Hand.OFF
                    ? mc.player.getOffhandItem()
                    : mc.player.getInventory().getItem(source.slot());
        }
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
