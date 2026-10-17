package me.alpha432.oyvey.util.prediction;

import me.alpha432.oyvey.util.prediction.InterceptSolver.*;

import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Dependency-free checks for {@link InterceptSolver}. Run with:
 * <pre>
 *   javac -d out src/main/java/me/alpha432/oyvey/util/prediction/InterceptSolver.java \
 *                src/test/java/me/alpha432/oyvey/util/prediction/InterceptSolverCheck.java
 *   java -cp out me.alpha432.oyvey.util.prediction.InterceptSolverCheck
 * </pre>
 * The forward simulation below is written separately from the solver's algebra, so these checks verify the
 * math and angle conversion. They do not (and cannot) verify that the formulas match the real game.
 */
public final class InterceptSolverCheck {
    private static int failures = 0;

    public static void main(String[] args) {
        angleRoundTrip();
        standingPlayer();
        diagonalRunnerNeedsMomentum();
        burstChangesPrediction();
        movingShooterSpawnsAtReportedPosition();
        frictionScaledVelocityWouldMiss();
        impactCutoff();
        clearanceBlocksShot();
        fuzz();
        if (failures > 0) {
            System.out.println("FAILED: " + failures);
            System.exit(1);
        }
        System.out.println("All checks passed.");
    }

    // ---- independent reference simulation ------------------------------------------------------------------

    /** Pearl position after {@code ticks}, stepping exactly like the in-game entity (no bursts). */
    static V3 simPearl(V3 p, V3 v, int ticks) {
        for (int i = 0; i < ticks; i++) {
            v = new V3(v.x(), v.y() - 0.03, v.z()).scale(0.99);
            p = p.add(v);
        }
        return p;
    }

    /** Wind charge position {@code s} (fractional) ticks after spawning. */
    static V3 fracCharge(V3 spawn, double yaw, double pitch, V3 momentum, double s) {
        V3 v = InterceptSolver.directionOf(yaw, pitch).scale(1.5).add(momentum);
        return spawn.add(v.scale(s));
    }

    /** Wind charge position {@code k} ticks after spawning at {@code spawn}. */
    static V3 simCharge(V3 spawn, double yaw, double pitch, V3 momentum, int k) {
        V3 v = InterceptSolver.directionOf(yaw, pitch).scale(1.5).add(momentum);
        return spawn.add(v.scale(k));
    }

    /** Distance from point p to segment a-b. */
    static double segDist(V3 p, V3 a, V3 b) {
        V3 ab = b.sub(a), ap = p.sub(a);
        double len2 = ab.x() * ab.x() + ab.y() * ab.y() + ab.z() * ab.z();
        double u = len2 < 1e-12 ? 0 : (ap.x() * ab.x() + ap.y() * ab.y() + ap.z() * ab.z()) / len2;
        u = Math.max(0, Math.min(1, u));
        return p.distanceTo(a.add(ab.scale(u)));
    }

    /** Distance between the pearl at tick t and the swept path of the charge during its k-th tick. */
    static double sweepMiss(V3 spawn, double yaw, double pitch, V3 momentum, int k, V3 pearlAtT) {
        V3 v = InterceptSolver.directionOf(yaw, pitch).scale(1.5).add(momentum);
        return segDist(pearlAtT, spawn.add(v.scale(k - 1)), spawn.add(v.scale(k)));
    }

    static V3 pearlLaunch(double yaw, double pitch) {
        return InterceptSolver.directionOf(yaw, pitch).scale(1.5);
    }

    static Request request(V3 pPos, V3 pVel, V3 eye, V3 vel, boolean ground, int lead, int window, double hit) {
        return new Request(pPos, pVel, eye, vel, ground, lead, window, hit, Pick.BEST_FIT, List.of(), Clearance.ALWAYS);
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    // ---- tests ---------------------------------------------------------------------------------------------

    static void angleRoundTrip() {
        System.out.println("angle round trip");
        Random rnd = new Random(1);
        boolean ok = true;
        for (int i = 0; i < 5000; i++) {
            double yaw = rnd.nextDouble() * 358 - 179, pitch = rnd.nextDouble() * 178 - 89;
            float[] a = InterceptSolver.anglesOf(InterceptSolver.directionOf(yaw, pitch));
            ok &= Math.abs(a[0] - yaw) < 1e-3 && Math.abs(a[1] - pitch) < 1e-3;
        }
        check(ok, "anglesOf(directionOf(yaw,pitch)) == (yaw,pitch)");
        V3 plusZ = InterceptSolver.directionOf(0, 0), up = InterceptSolver.directionOf(0, -90);
        check(plusZ.z() > 0.999 && up.y() > 0.999, "yaw 0 looks +Z, pitch -90 looks up (vanilla convention)");
    }

    static void standingPlayer() {
        System.out.println("standing player, pearl flying across");
        V3 pPos = new V3(0, 65.6, 0), pVel = pearlLaunch(-80, -25);
        V3 eye = new V3(25, 65.6, 6);
        Request r = request(pPos, pVel, eye, V3.ZERO, true, 2, 30, 0.55);
        Optional<Solution> s = InterceptSolver.solve(r);
        check(s.isPresent(), "a solution exists");
        s.ifPresent(sol -> {
            V3 pearl = simPearl(pPos, pVel, sol.arrivalTick());
            double exact = simCharge(eye, sol.yaw(), sol.pitch(), V3.ZERO, 1).distanceTo(eye); // sanity: speed 1.5
            check(Math.abs(exact - 1.5) < 1e-6, "launch speed is 1.5 b/t with no momentum");
            double atContact = fracCharge(eye, sol.yaw(), sol.pitch(), V3.ZERO, sol.contactTime()).distanceTo(pearl);
            check(atContact < 0.01, String.format("path passes through the pearl at s=%.2f (error %.4f)", sol.contactTime(), atContact));
            double miss = sweepMiss(eye, sol.yaw(), sol.pitch(), V3.ZERO, sol.flightTicks(), pearl);
            check(miss <= 0.55 + 1e-4, String.format("pearl lies in the charge's sweep (miss %.3f, tick %d)", miss, sol.arrivalTick()));
        });
    }

    static void diagonalRunnerNeedsMomentum() {
        System.out.println("player sprinting diagonally (momentum compensation)");
        V3 pPos = new V3(0, 65.6, 0), pVel = pearlLaunch(-80, -25);
        V3 eye = new V3(25, 65.6, 6);
        V3 vel = new V3(0.22, 0, 0.22);                    // sprint speed on both axes
        int lead = 2;
        Optional<Solution> s = InterceptSolver.solve(request(pPos, pVel, eye, vel, true, lead, 30, 0.55));
        check(s.isPresent(), "a solution exists");
        s.ifPresent(sol -> {
            V3 spawn = eye;   // the server spawns the charge where the last movement packet put the shooter
            V3 pearl = simPearl(pPos, pVel, sol.arrivalTick());
            double withMomentum = sweepMiss(spawn, sol.yaw(), sol.pitch(), vel, sol.flightTicks(), pearl);
            double ignoring = sweepMiss(spawn, sol.yaw(), sol.pitch(), V3.ZERO, sol.flightTicks(), pearl);
            check(withMomentum <= 0.55 + 1e-4, String.format("hits when momentum is applied (miss %.3f)", withMomentum));
            check(ignoring > 0.55, String.format("same angles would miss if momentum were ignored (miss %.3f)", ignoring));
        });
        // an airborne player also carries vertical momentum
        Optional<Solution> air = InterceptSolver.solve(request(pPos, pVel, eye, new V3(0.1, 0.3, 0.1), false, lead, 30, 0.55));
        check(air.isPresent(), "airborne shooter (vertical momentum) also solves");
    }

    static void burstChangesPrediction() {
        System.out.println("wind-charge burst applied to the pearl prediction");
        V3 pPos = new V3(0, 65.6, 0), pVel = pearlLaunch(-80, -25);
        V3[] plain = InterceptSolver.predictPearl(pPos, pVel, 30, List.of());
        V3 at8 = plain[8];
        Burst burst = new Burst(8, at8.add(new V3(0, -0.6, 0)), 1.2, 1.22);   // blast just below the pearl
        V3[] pushed = InterceptSolver.predictPearl(pPos, pVel, 30, List.of(burst));
        check(plain[8].distanceTo(pushed[8]) < 1e-9, "path is identical up to the burst tick");
        check(pushed[12].y() > plain[12].y() + 0.5, "pearl is pushed upward after the burst");
        check(plain[25].distanceTo(pushed[25]) > 2.0, "paths diverge substantially later on");

        // deriveBursts finds a charge that will actually touch the pearl, and ignores one that will not
        V3 chargeVel = new V3(0, 0, 0);
        Charge hitting = new Charge(at8, chargeVel);
        Charge missing = new Charge(at8.add(new V3(0, 50, 0)), chargeVel);
        List<Burst> found = InterceptSolver.deriveBursts(pPos, pVel, List.of(hitting, missing), 30, 0.55, 1.2, 1.22);
        check(found.size() == 1, "one of two in-flight charges is predicted to burst on the pearl");

        // solve against the pushed path and verify against that same path
        V3 eye = new V3(25, 68, 6);
        Request r = new Request(pPos, pVel, eye, V3.ZERO, true, 1, 30, 0.55, Pick.BEST_FIT, List.of(burst), Clearance.ALWAYS);
        Optional<Solution> s = InterceptSolver.solve(r);
        check(s.isPresent(), "a solution exists for the deflected pearl");
        s.ifPresent(sol -> {
            double miss = sweepMiss(eye, sol.yaw(), sol.pitch(), V3.ZERO, sol.flightTicks(), pushed[sol.arrivalTick()]);
            check(miss <= 0.55 + 1e-4, String.format("charge meets the deflected pearl (miss %.3f)", miss));
        });
    }

    /** Several shooter motions and latencies; every solution is re-simulated from the eye position. */
    static void movingShooterSpawnsAtReportedPosition() {
        System.out.println("moving shooter: spawn is the reported position, whatever the latency");
        V3 pPos = new V3(0, 65.6, 0), pVel = pearlLaunch(-80, -25);
        V3 eye = new V3(25, 65.6, 6);
        V3[] motions = {
                new V3(0.28, 0, 0), new V3(-0.28, 0, 0), new V3(0, 0, 0.28), new V3(0, 0, -0.28),   // sprint on an axis
                new V3(0.2, 0, 0.2), new V3(-0.2, 0, 0.2), new V3(0.2, 0, -0.2), new V3(-0.2, 0, -0.2), // diagonals
                new V3(0.1, 0.42, 0.1), new V3(0, -0.6, 0), new V3(0.15, -1.2, -0.15),             // jumping / falling
        };
        int solved = 0, failed = 0, total = 0;
        for (V3 vel : motions) {
            boolean ground = vel.y() == 0;
            for (int lead = 0; lead <= 6; lead++) {
                total++;
                Optional<Solution> s = InterceptSolver.solve(request(pPos, pVel, eye, vel, ground, lead, 40, 0.55));
                if (s.isEmpty()) continue;
                solved++;
                Solution sol = s.get();
                V3 m = new V3(vel.x(), ground ? 0 : vel.y(), vel.z());
                double miss = sweepMiss(eye, sol.yaw(), sol.pitch(), m, sol.flightTicks(), simPearl(pPos, pVel, sol.arrivalTick()));
                if (miss > 0.55 + 0.02) failed++;
            }
        }
        check(solved > total / 2, "solutions exist for most motion/latency combinations (" + solved + "/" + total + ")");
        check(failed == 0, "every solution re-simulates inside the hit radius (" + failed + " failures)");
    }

    /**
     * Size of the error if the client's friction-scaled velocity were used as the launch momentum while the server
     * applies the real per-tick movement. This quantifies the effect only; whether the server really uses the
     * displacement is a game-mechanics claim that needs in-game confirmation.
     */
    static void frictionScaledVelocityWouldMiss() {
        System.out.println("magnitude of the momentum error for a ground sprinter");
        V3 pPos = new V3(0, 65.6, 0), pVel = pearlLaunch(-80, -25);
        V3 eye = new V3(25, 65.6, 6);
        V3 actual = new V3(0.2, 0, 0.2);                       // per-tick displacement
        V3 scaled = actual.scale(0.6 * 0.91);                  // ground friction applied: what getDeltaMovement holds
        Optional<Solution> s = InterceptSolver.solve(request(pPos, pVel, eye, scaled, true, 2, 30, 0.55));
        check(s.isPresent(), "a solution exists for the scaled velocity");
        s.ifPresent(sol -> {
            double miss = sweepMiss(eye, sol.yaw(), sol.pitch(), actual, sol.flightTicks(), simPearl(pPos, pVel, sol.arrivalTick()));
            check(miss > 0.55, String.format("aim computed from the scaled velocity misses the real throw by %.2f blocks", miss));
        });
    }

    static void impactCutoff() {
        System.out.println("pearl impact cut-off");
        V3 pPos = new V3(0, 65.6, 0), pVel = pearlLaunch(-80, -25);
        V3 eye = new V3(25, 65.6, 6);
        Optional<Solution> free = InterceptSolver.solve(new Request(pPos, pVel, eye, V3.ZERO, true, 2, 30, 0.55,
                Pick.EARLIEST, List.of(), Clearance.ALWAYS, Integer.MAX_VALUE));
        check(free.isPresent(), "free flight: a solution exists");
        int cap = free.map(sol -> sol.arrivalTick() - 1).orElse(0);
        Optional<Solution> capped = InterceptSolver.solve(new Request(pPos, pVel, eye, V3.ZERO, true, 2, 30, 0.55,
                Pick.EARLIEST, List.of(), Clearance.ALWAYS, cap));
        check(capped.isEmpty() || capped.get().arrivalTick() <= cap, "no solution later than the impact tick");
        check(InterceptSolver.solve(new Request(pPos, pVel, eye, V3.ZERO, true, 2, 30, 0.55,
                Pick.EARLIEST, List.of(), Clearance.ALWAYS, 2)).isEmpty(), "pearl gone before any charge could arrive: no solution");
    }

    static void clearanceBlocksShot() {
        System.out.println("line of flight");
        V3 pPos = new V3(0, 65.6, 0), pVel = pearlLaunch(-80, -25);
        V3 eye = new V3(25, 65.6, 6);
        Request open = request(pPos, pVel, eye, V3.ZERO, true, 2, 30, 0.55);
        Request walled = new Request(pPos, pVel, eye, V3.ZERO, true, 2, 30, 0.55, Pick.BEST_FIT, List.of(), (a, b) -> false);
        check(InterceptSolver.solve(open).isPresent(), "open sky: solution");
        check(InterceptSolver.solve(walled).isEmpty(), "fully blocked: no solution");
    }

    static void fuzz() {
        System.out.println("fuzz: 20000 random scenarios, every returned solution is re-simulated");
        Random rnd = new Random(42);
        int solved = 0, bad = 0;
        for (int i = 0; i < 20000; i++) {
            V3 pPos = new V3(rnd.nextDouble() * 60 - 30, 64 + rnd.nextDouble() * 10, rnd.nextDouble() * 60 - 30);
            V3 pVel = pearlLaunch(rnd.nextDouble() * 360 - 180, -rnd.nextDouble() * 50);
            V3 eye = new V3(rnd.nextDouble() * 40 - 20, 65 + rnd.nextDouble() * 4, rnd.nextDouble() * 40 - 20);
            boolean ground = rnd.nextBoolean();
            V3 vel = new V3(rnd.nextDouble() * 0.5 - 0.25, ground ? 0 : rnd.nextDouble() * 0.8 - 0.4, rnd.nextDouble() * 0.5 - 0.25);
            int lead = rnd.nextInt(6);
            double hit = 0.35 + rnd.nextDouble() * 0.5;
            Pick pick = Pick.values()[rnd.nextInt(3)];
            int valid = rnd.nextBoolean() ? Integer.MAX_VALUE : rnd.nextInt(31);
            Request r = new Request(pPos, pVel, eye, vel, ground, lead, 30, hit, pick, List.of(), Clearance.ALWAYS, valid);
            Optional<Solution> s = InterceptSolver.solve(r);
            if (s.isEmpty()) continue;
            solved++;
            Solution sol = s.get();

            // independent re-simulation of the charge: spawns at the eye with the shooter's reported movement
            V3 m = new V3(vel.x(), ground ? 0 : vel.y(), vel.z());
            double miss = sweepMiss(eye, sol.yaw(), sol.pitch(), m, sol.flightTicks(), simPearl(pPos, pVel, sol.arrivalTick()));
            // float rounding of yaw/pitch costs a hair of accuracy at long range
            if (miss > hit + 0.02 || sol.arrivalTick() > 30 || sol.arrivalTick() <= lead || sol.arrivalTick() > valid) bad++;
        }
        check(bad == 0, "no returned solution misses its hit radius (" + solved + " solved, " + bad + " bad)");
        check(solved > 500, "solver finds intercepts in a healthy share of cases (" + solved + "/20000)");
    }
}
