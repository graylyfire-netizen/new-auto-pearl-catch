package me.alpha432.oyvey.util.prediction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Pure-math solver that works out where to aim a wind charge so that it meets an ender pearl
 * that is already in flight. It deliberately has no Minecraft imports so it can be tested on its own.
 *
 * <h3>Model (vanilla 1.21.x, per tick)</h3>
 * <ul>
 *   <li><b>Pearl:</b> {@code v.y -= 0.03; v *= 0.99; pos += v}</li>
 *   <li><b>Wind charge:</b> constant velocity (no gravity, inertia 1.0). Launch velocity is
 *       {@code 1.5 * aimDirection + shooterMomentum}, where momentum is the shooter's x/z velocity and
 *       its y velocity only while airborne. That momentum term is what makes "diagonal" throws work:
 *       the aim has to cancel the sideways drift of a moving player.</li>
 *   <li><b>Latency:</b> the charge does not spawn on the server until about {@code latencyTicks} after the client's
 *       view of the pearl, so the <i>pearl</i> is predicted forward that far. The <i>shooter</i> is not: the use-item
 *       packet is sent right after this tick's movement packet, so the server spawns the charge at the position
 *       that packet reported and applies the movement it implies (see {@link Request#playerVel()}).</li>
 * </ul>
 *
 * <h3>Solving</h3>
 * A charge spawned at {@code S} with momentum {@code m} is at {@code S + s (1.5 u + m)} after {@code s} ticks.
 * For it to pass through pearl position {@code P(t)} we need {@code |D - s m| = 1.5 s} with {@code D = P - S},
 * which is a quadratic in the contact time {@code s}; the aim is then {@code u = (D/s - m) / 1.5}.
 * <p>
 * The game does not sample a projectile once per tick, it sweeps its hitbox along that tick's 1.5-block step. So
 * a candidate is valid when the contact time falls inside the sweep of the tick in which the pearl is at
 * {@code P(t)}, i.e. {@code s in [k-1, k]} with {@code k = t - latency}. If it falls outside, the along-track
 * overshoot (in blocks) is the miss, and candidates with a miss above {@code hitRadius} are dropped. One of the
 * remaining candidates is chosen according to {@link Pick}.
 *
 * <h3>Known limits</h3>
 * The pearl is predicted as free flight only. Callers that can see the world should pass
 * {@link Request#pearlValidTicks()} so intercepts beyond a predicted block impact are not offered.
 * Vanilla applies a small random spread to thrown wind charges (about +-0.026 b/t per axis), which is not modelled,
 * and nothing here checks whether vanilla registers a wind-charge/pearl contact; see the module docs.
 */
public final class InterceptSolver {
    public static final double PEARL_GRAVITY = 0.03;
    public static final double PEARL_DRAG = 0.99;
    public static final double WIND_CHARGE_SPEED = 1.5;

    private InterceptSolver() {
        throw new AssertionError();
    }

    public record V3(double x, double y, double z) {
        public static final V3 ZERO = new V3(0, 0, 0);

        public V3 add(V3 o) {
            return new V3(x + o.x, y + o.y, z + o.z);
        }

        public V3 sub(V3 o) {
            return new V3(x - o.x, y - o.y, z - o.z);
        }

        public V3 scale(double s) {
            return new V3(x * s, y * s, z * s);
        }

        public double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }

        public double distanceTo(V3 o) {
            return sub(o).length();
        }

        public V3 normalized() {
            double len = length();
            return len < 1.0E-9 ? ZERO : scale(1.0 / len);
        }
    }

    /** Which feasible intercept to take. */
    public enum Pick {
        /** Intercept where the pearl is nearest to the player (default). */
        CLOSEST,
        /** Intercept as soon as possible. */
        EARLIEST,
        /** Intercept with the smallest miss, preferring contact mid-sweep (most tolerant of tick-order error). */
        BEST_FIT
    }

    /** A wind-charge explosion that will shove the pearl on the given tick. */
    public record Burst(int tick, V3 center, double radius, double knockback) {
    }

    /** A wind charge that is already flying (position and per-tick velocity). */
    public record Charge(V3 pos, V3 vel) {
    }

    /** Line-of-flight check supplied by the caller (a block raycast in the game). */
    @FunctionalInterface
    public interface Clearance {
        Clearance ALWAYS = (from, to) -> true;

        boolean isClear(V3 from, V3 to);
    }

    /**
     * @param playerVel       the shooter's <i>known movement</i> in blocks per tick: the displacement of the most
     *                        recent movement update (position minus previous-tick position), which is what the
     *                        server adds to a projectile's launch velocity. This is not the friction-scaled value
     *                        returned by {@code getDeltaMovement()}. Its y component only counts while airborne.
     * @param pearlValidTicks last tick index (0..windowTicks) at which the pearl is still predicted to be in flight,
     *                        e.g. the tick before it hits a block; intercepts after it are ignored
     */
    public record Request(
            V3 pearlPos, V3 pearlVel,
            V3 eyePos, V3 playerVel, boolean playerOnGround,
            int latencyTicks, int windowTicks,
            double hitRadius, Pick pick,
            List<Burst> bursts, Clearance clearance,
            int pearlValidTicks) {
        /** Free-flight pearl: no impact cut-off. */
        public Request(V3 pearlPos, V3 pearlVel, V3 eyePos, V3 playerVel, boolean playerOnGround,
                       int latencyTicks, int windowTicks, double hitRadius, Pick pick,
                       List<Burst> bursts, Clearance clearance) {
            this(pearlPos, pearlVel, eyePos, playerVel, playerOnGround, latencyTicks, windowTicks, hitRadius, pick,
                    bursts, clearance, Integer.MAX_VALUE);
        }
    }

    public record Solution(
            float yaw, float pitch, V3 aim,
            int arrivalTick, int flightTicks,
            V3 intercept, double miss, double playerDistance, double contactTime) {
    }

    /**
     * Predicts the pearl's position for ticks 0..ticks (index 0 is the current position).
     * Bursts shove the pearl after it has moved on their tick.
     */
    public static V3[] predictPearl(V3 pos, V3 vel, int ticks, List<Burst> bursts) {
        V3[] out = new V3[ticks + 1];
        out[0] = pos;
        double x = pos.x(), y = pos.y(), z = pos.z();
        double vx = vel.x(), vy = vel.y(), vz = vel.z();
        for (int i = 1; i <= ticks; i++) {
            vy -= PEARL_GRAVITY;
            vx *= PEARL_DRAG;
            vy *= PEARL_DRAG;
            vz *= PEARL_DRAG;
            x += vx;
            y += vy;
            z += vz;
            out[i] = new V3(x, y, z);
            for (Burst b : bursts) {
                if (b.tick() != i) continue;
                V3 away = out[i].sub(b.center());
                double dist = away.length();
                double d = dist / (2.0 * b.radius());
                if (d >= 1.0) continue;
                V3 dir = dist < 1.0E-6 ? new V3(0, 1, 0) : away.scale(1.0 / dist);
                double push = (1.0 - d) * b.knockback();
                vx += dir.x() * push;
                vy += dir.y() * push;
                vz += dir.z() * push;
            }
        }
        return out;
    }

    /**
     * Works out which of the given in-flight wind charges will touch the pearl (straight-line flight, blocks
     * ignored) and returns the resulting bursts so the prediction can account for them.
     */
    public static List<Burst> deriveBursts(V3 pearlPos, V3 pearlVel, List<Charge> charges, int window,
                                           double contactRadius, double blastRadius, double knockback) {
        List<Burst> out = new ArrayList<>();
        if (charges.isEmpty()) return out;
        V3[] base = predictPearl(pearlPos, pearlVel, window, List.of());
        for (Charge c : charges) {
            for (int k = 1; k <= window; k++) {
                V3 w = c.pos().add(c.vel().scale(k));
                if (w.distanceTo(base[k]) <= contactRadius) {
                    out.add(new Burst(k, w, blastRadius, knockback));
                    break;
                }
            }
        }
        return out;
    }

    public static Optional<Solution> solve(Request r) {
        final int lead = Math.max(0, r.latencyTicks());
        final int window = r.windowTicks();
        if (window <= lead) return Optional.empty();

        V3[] pearl = predictPearl(r.pearlPos(), r.pearlVel(), window, r.bursts());

        // The charge spawns where the last movement packet put the shooter, carrying that packet's movement
        // (vertical part only while airborne). The shooter's later movement cannot affect a charge that has left.
        final boolean airborne = !r.playerOnGround();
        final V3 spawn = r.eyePos();
        final V3 momentum = new V3(r.playerVel().x(), airborne ? r.playerVel().y() : 0.0, r.playerVel().z());
        final int lastTick = Math.min(window, r.pearlValidTicks());

        Solution best = null;
        Comparator<Solution> order = comparatorFor(r.pick());
        final double mm = momentum.x() * momentum.x() + momentum.y() * momentum.y() + momentum.z() * momentum.z();
        final double a = WIND_CHARGE_SPEED * WIND_CHARGE_SPEED - mm;

        for (int t = lead + 1; t <= lastTick; t++) {
            final int k = t - lead;
            final V3 target = pearl[t];
            final V3 d = target.sub(spawn);
            final double dd = dot(d, d);
            if (dd < 1.0E-9) continue;
            final double dm = dot(d, momentum);

            // a*s^2 + 2*dm*s - dd = 0 ; usually one positive root, but a shooter faster than the charge has two
            double[] roots;
            if (Math.abs(a) < 1.0E-9) {
                if (dm <= 0) continue;
                roots = new double[]{dd / (2.0 * dm)};
            } else {
                double disc = dm * dm + a * dd;
                if (disc < 0) continue;
                double sq = Math.sqrt(disc);
                roots = new double[]{(-dm + sq) / a, (-dm - sq) / a};
            }

            for (double s : roots) {
                if (s <= 1.0E-6) continue;
                double overshootTicks = Math.max(0.0, Math.max((k - 1) - s, s - k));
                double speed = Math.sqrt(dd) / s;                 // |launch velocity|, since S + s*v = P
                double miss = overshootTicks * speed;
                if (miss > r.hitRadius()) continue;
                if (!r.clearance().isClear(spawn, target)) continue;

                V3 aim = d.scale(1.0 / s).sub(momentum);
                if (aim.length() < 1.0E-6) continue;
                aim = aim.normalized();
                float[] angles = anglesOf(aim);
                Solution candidate = new Solution(angles[0], angles[1], aim, t, k, target, miss,
                        target.distanceTo(r.eyePos()), s);
                if (best == null || order.compare(candidate, best) < 0) best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    private static double dot(V3 a, V3 b) {
        return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
    }

    private static Comparator<Solution> comparatorFor(Pick pick) {
        return switch (pick) {
            case EARLIEST -> Comparator.comparingInt(Solution::arrivalTick);
            case BEST_FIT -> Comparator.comparingDouble(Solution::miss)
                    .thenComparingDouble(sol -> Math.abs(sol.contactTime() - (sol.flightTicks() - 0.5)))
                    .thenComparingInt(Solution::arrivalTick);
            case CLOSEST -> Comparator.comparingDouble(Solution::playerDistance).thenComparingInt(Solution::arrivalTick);
        };
    }

    /** Minecraft yaw/pitch (degrees) for a direction vector: yaw 0 looks +Z, pitch -90 looks straight up. */
    public static float[] anglesOf(V3 dir) {
        V3 u = dir.normalized();
        double yaw = Math.toDegrees(Math.atan2(-u.x(), u.z()));
        double pitch = -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, u.y()))));
        return new float[]{(float) yaw, (float) pitch};
    }

    /** Inverse of {@link #anglesOf}; mirrors how vanilla turns rotation into a shot direction. */
    public static V3 directionOf(double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        return new V3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
    }
}
