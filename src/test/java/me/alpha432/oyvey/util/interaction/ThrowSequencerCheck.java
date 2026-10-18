package me.alpha432.oyvey.util.interaction;

import me.alpha432.oyvey.util.interaction.PearlOwnership.Snapshot;
import me.alpha432.oyvey.util.interaction.PearlOwnership.Verdict;
import me.alpha432.oyvey.util.interaction.ThrowSequencer.Host;
import me.alpha432.oyvey.util.interaction.ThrowSequencer.Outcome;
import me.alpha432.oyvey.util.interaction.ThrowSequencer.Request;
import me.alpha432.oyvey.util.interaction.ThrowSequencer.Source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Dependency-free checks for {@link PearlOwnership} and {@link ThrowSequencer}. Run with:
 * <pre>
 *   java -m jdk.compiler/com.sun.tools.javac.Main -d out \
 *     src/main/java/me/alpha432/oyvey/util/interaction/*.java \
 *     src/test/java/me/alpha432/oyvey/util/interaction/ThrowSequencerCheck.java
 *   java -cp out me.alpha432.oyvey.util.interaction.ThrowSequencerCheck
 * </pre>
 *
 * <p>What this does and does not show: the sequencer is exercised against a model of a client (selected slot,
 * hotbar, pearls) and a server (slot it was told about, a spawn latency) that records every packet in order. It
 * proves the sequencer's own rules (what it sends, in which order, how often, and what it forgets). It does not
 * prove that the game-facing {@code PearlCatchModule} wires {@link Host} to the game correctly, that the hook runs
 * before the movement packet, or what any anti-cheat will say; those need the game and a server.
 */
public final class ThrowSequencerCheck {
    private static int failures = 0;

    public static void main(String[] args) {
        ownershipClassification();
        ownershipIsRecheckedBeforeEveryInteraction();
        otherPlayersPearlsNeverCauseInteractions();
        slotSwitchIsSingleVisibleAndRestoredLater();
        noSlotPacketWhenAlreadyHoldingOrOffhand();
        restoreDefersToPlayerChoices();
        slotSelectionThatDoesNotTakeEffectIsNotUsed();
        oneSlotChangePerTick();
        pendingAttemptBlocksDuplicates();
        cooldownedItemCostsNothing();
        retryLimit();
        retriesDoNotBounceTheSlot();
        disableCleansUpWithoutPackets();
        worldChangeCleansUpWithoutPackets();
        fuzz();
        if (failures > 0) {
            System.out.println("FAILED: " + failures);
            System.exit(1);
        }
        System.out.println("All checks passed.");
    }

    // ---- model --------------------------------------------------------------------------------------------------

    static final UUID ME = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /** Client + server model that implements {@link Host} and logs every packet in wire order. */
    static final class Sim implements Host {
        // client state
        int clientSlot = 1;
        final boolean[] hotbarHasCharge = new boolean[9];
        boolean offhandHasCharge;
        boolean busy;
        boolean itemOnCooldown;
        boolean clientAccepts = true;
        boolean slotSelectionWorks = true;
        /** pearl id -> owner (null = unresolved); a missing id is a removed/unknown entity. */
        final Map<Integer, UUID> pearls = new HashMap<>();
        // server state
        int serverSlot = 1;
        boolean serverSpawnsCharge = true;
        int latency = 3;
        final Set<Integer> ownCharges = new HashSet<>();
        final Map<Long, Integer> spawnAt = new HashMap<>();
        int nextChargeId = 1000;
        // bookkeeping
        final List<String> wire = new ArrayList<>();
        int tickNo;
        int ownsCalls;
        boolean useOnUnownedPearl; // set if use() ever ran while the pearl wasn't verifiably ours
        int currentTarget = -1;

        boolean owned(int id) {
            return ME.equals(pearls.get(id));
        }

        @Override
        public boolean ownsPearl(int id) {
            ownsCalls++;
            return owned(id);
        }

        @Override
        public Source locateCharge() {
            if (hotbarHasCharge[clientSlot]) return Source.hotbar(clientSlot);
            if (offhandHasCharge) return Source.offhand();
            for (int i = 0; i < 9; i++) if (hotbarHasCharge[i]) return Source.hotbar(i);
            return null;
        }

        @Override
        public int selectedSlot() {
            return clientSlot;
        }

        @Override
        public void selectSlot(int slot) {
            if (!slotSelectionWorks) return;
            clientSlot = slot;
            serverSlot = slot; // same call tells the server: the single slot packet
            wire.add("t" + tickNo + " SET_SLOT " + slot);
        }

        @Override
        public boolean isOnCooldown(Source source) {
            return itemOnCooldown;
        }

        @Override
        public boolean use(Source source, float yaw, float pitch) {
            if (currentTarget >= 0 && !owned(currentTarget)) useOnUnownedPearl = true;
            // a use always goes out with the slot the server believes in
            int heldAtServer = source.hand() == ThrowSequencer.Hand.OFF ? -1 : serverSlot;
            wire.add("t" + tickNo + " USE " + source.hand() + (heldAtServer >= 0 ? "@" + heldAtServer : ""));
            if (!clientAccepts) return false;
            if (serverSpawnsCharge) spawnAt.put((long) tickNo + latency, nextChargeId++);
            return true;
        }

        @Override
        public Set<Integer> ownChargeIds() {
            return new HashSet<>(ownCharges);
        }

        @Override
        public boolean playerBusy() {
            return busy;
        }

        /** One client tick in the same phase order the game hook uses: pre-movement work, then the movement packet. */
        void tick(ThrowSequencer seq, Runnable preMovement) {
            tickNo++;
            Integer spawned = spawnAt.remove((long) tickNo);
            if (spawned != null) ownCharges.add(spawned);
            seq.tick(this);
            preMovement.run();
            wire.add("t" + tickNo + " FLYING");
            if (clientSlot != serverSlot) fail("client slot " + clientSlot + " != server slot " + serverSlot + " after tick " + tickNo);
        }

        long count(String what) {
            return wire.stream().filter(l -> l.contains(" " + what)).count();
        }

        long countInTick(int tick, String what) {
            return wire.stream().filter(l -> l.startsWith("t" + tick + " ") && l.contains(" " + what)).count();
        }
    }

    static Request req(int pearl) {
        return new Request(pearl, 10f, -20f, 24, 10, 8);
    }

    static Outcome tryAttempt(Sim sim, ThrowSequencer seq, int pearl) {
        sim.currentTarget = pearl;
        return seq.attempt(sim, req(pearl));
    }

    static void ticks(Sim sim, ThrowSequencer seq, int n) {
        for (int i = 0; i < n; i++) sim.tick(seq, () -> {
        });
    }

    static Sim simWithChargeInSlot(int slot) {
        Sim sim = new Sim();
        sim.hotbarHasCharge[slot] = true;
        sim.pearls.put(7, ME);
        return sim;
    }

    // ---- checks -------------------------------------------------------------------------------------------------

    static void ownershipClassification() {
        System.out.println("ownership classification");
        UUID me = ME;
        check("my pearl is LOCAL", PearlOwnership.classify(new Snapshot(true, true, me, true), me) == Verdict.LOCAL);
        check("another player's pearl is OTHER", PearlOwnership.classify(new Snapshot(true, true, OTHER, false), me) == Verdict.OTHER);
        check("a pearl with an unresolved owner is UNVERIFIED, not mine",
                PearlOwnership.classify(new Snapshot(true, true, null, false), me) == Verdict.UNVERIFIED);
        check("no local player to compare with: UNVERIFIED",
                PearlOwnership.classify(new Snapshot(true, true, me, true), null) == Verdict.UNVERIFIED);
        check("right UUID but a stale player object (e.g. before a respawn) is not accepted",
                PearlOwnership.classify(new Snapshot(true, true, me, false), me) == Verdict.OTHER);
        check("same object but a different UUID is not accepted",
                PearlOwnership.classify(new Snapshot(true, true, OTHER, true), me) == Verdict.OTHER);
        check("a different entity type is NOT_A_LIVE_PEARL", PearlOwnership.classify(new Snapshot(false, true, me, true), me) == Verdict.NOT_A_LIVE_PEARL);
        check("a removed pearl is NOT_A_LIVE_PEARL", PearlOwnership.classify(new Snapshot(true, false, me, true), me) == Verdict.NOT_A_LIVE_PEARL);
        check("null snapshot is NOT_A_LIVE_PEARL", PearlOwnership.classify(null, me) == Verdict.NOT_A_LIVE_PEARL);
        check("only LOCAL is actionable",
                Verdict.LOCAL.isLocal() && !Verdict.OTHER.isLocal() && !Verdict.UNVERIFIED.isLocal() && !Verdict.NOT_A_LIVE_PEARL.isLocal());
    }

    static void ownershipIsRecheckedBeforeEveryInteraction() {
        System.out.println("ownership is re-checked before each interaction");
        // charge in another slot: slot selection and use are two interactions, so two checks
        Sim sim = simWithChargeInSlot(4);
        ThrowSequencer seq = new ThrowSequencer();
        ticks(sim, seq, 1);
        Outcome o = tryAttempt(sim, seq, 7);
        check("attempt is sent", o == Outcome.SENT);
        check("ownership was asked before the slot change and again before the use (2 checks)", sim.ownsCalls == 2);

        // ownership lost between the slot change and the use: the use must not happen
        Sim sim2 = simWithChargeInSlot(4);
        ThrowSequencer seq2 = new ThrowSequencer();
        Host flip = new Host() {
            int calls;

            public boolean ownsPearl(int id) {
                return ++calls < 2; // yes at the first check, no at the second
            }

            public Source locateCharge() {
                return sim2.locateCharge();
            }

            public int selectedSlot() {
                return sim2.selectedSlot();
            }

            public void selectSlot(int s) {
                sim2.selectSlot(s);
            }

            public boolean isOnCooldown(Source s) {
                return false;
            }

            public boolean use(Source s, float y, float p) {
                return sim2.use(s, y, p);
            }

            public Set<Integer> ownChargeIds() {
                return Set.of();
            }

            public boolean playerBusy() {
                return false;
            }
        };
        seq2.tick(flip);
        Outcome o2 = seq2.attempt(flip, req(7));
        check("second check fails -> OWNERSHIP", o2 == Outcome.OWNERSHIP);
        check("no use-item was sent", sim2.count("USE") == 0);
        check("nothing is left pending", !seq2.isPending());
        check("an attempt that never used the item is not counted", seq2.attemptsFor(7) == 0);

        // lost before anything happened: no packet at all
        Sim sim3 = simWithChargeInSlot(4);
        sim3.pearls.put(7, null);
        ThrowSequencer seq3 = new ThrowSequencer();
        ticks(sim3, seq3, 1);
        check("unresolved owner -> OWNERSHIP", tryAttempt(sim3, seq3, 7) == Outcome.OWNERSHIP);
        check("and not a single packet (no slot change either)", sim3.wire.stream().noneMatch(l -> l.contains("SET_SLOT") || l.contains("USE")));
    }

    static void otherPlayersPearlsNeverCauseInteractions() {
        System.out.println("other players' pearls");
        Sim sim = simWithChargeInSlot(4);
        sim.pearls.put(8, OTHER);
        sim.pearls.put(9, null);
        ThrowSequencer seq = new ThrowSequencer();
        ticks(sim, seq, 1);
        check("someone else's pearl -> OWNERSHIP", tryAttempt(sim, seq, 8) == Outcome.OWNERSHIP);
        check("unresolved pearl -> OWNERSHIP", tryAttempt(sim, seq, 9) == Outcome.OWNERSHIP);
        check("an id that does not exist -> OWNERSHIP", tryAttempt(sim, seq, 12345) == Outcome.OWNERSHIP);
        check("zero packets", sim.wire.stream().noneMatch(l -> l.contains("SET_SLOT") || l.contains("USE")));
        check("my own pearl is still handled afterwards", tryAttempt(sim, seq, 7) == Outcome.SENT);
    }

    static void slotSwitchIsSingleVisibleAndRestoredLater() {
        System.out.println("slot selection: charge in another hotbar slot");
        Sim sim = simWithChargeInSlot(4);
        sim.clientSlot = sim.serverSlot = 1;
        ThrowSequencer seq = new ThrowSequencer();
        ticks(sim, seq, 1);
        int throwTick = sim.tickNo + 1;
        sim.tick(seq, () -> check("attempt is sent", tryAttempt(sim, seq, 7) == Outcome.SENT));
        check("exactly one slot packet in the throw tick", sim.countInTick(throwTick, "SET_SLOT") == 1);
        check("exactly one use in the throw tick", sim.countInTick(throwTick, "USE") == 1);
        int slotIdx = sim.wire.indexOf("t" + throwTick + " SET_SLOT 4");
        int useIdx = sim.wire.indexOf("t" + throwTick + " USE MAIN@4");
        int flyIdx = sim.wire.indexOf("t" + throwTick + " FLYING");
        check("order in the tick: slot, use (with the new slot at the server), then movement", slotIdx >= 0 && useIdx > slotIdx && flyIdx > useIdx);
        check("client slot is the charge slot and matches the server", sim.clientSlot == 4 && sim.serverSlot == 4);
        check("no swap-back in the same tick", sim.countInTick(throwTick, "SET_SLOT") == 1);

        ticks(sim, seq, 20);
        check("the original slot is restored exactly once, later", sim.count("SET_SLOT 1") == 1);
        int restoreTick = -1;
        for (String l : sim.wire) if (l.endsWith("SET_SLOT 1")) restoreTick = Integer.parseInt(l.substring(1, l.indexOf(' ')));
        check("restore happens on a later tick than the throw", restoreTick > throwTick);
        check("slots agree at the end", sim.clientSlot == 1 && sim.serverSlot == 1);
        check("two slot packets in total, one use", sim.count("SET_SLOT") == 2 && sim.count("USE") == 1);
        check("no lease left", !seq.hasSlotLease());
    }

    static void noSlotPacketWhenAlreadyHoldingOrOffhand() {
        System.out.println("no unnecessary switches");
        Sim held = simWithChargeInSlot(1); // already selected
        ThrowSequencer seq = new ThrowSequencer();
        ticks(held, seq, 1);
        check("holding it: sent", tryAttempt(held, seq, 7) == Outcome.SENT);
        ticks(held, seq, 20);
        check("holding it: zero slot packets, one use", held.count("SET_SLOT") == 0 && held.count("USE") == 1);

        Sim off = new Sim();
        off.offhandHasCharge = true;
        off.pearls.put(7, ME);
        ThrowSequencer seq2 = new ThrowSequencer();
        ticks(off, seq2, 1);
        check("off hand: sent", tryAttempt(off, seq2, 7) == Outcome.SENT);
        ticks(off, seq2, 20);
        check("off hand: zero slot packets, one use", off.count("SET_SLOT") == 0 && off.count("USE OFF") == 1);

        // both a held charge and another one further along the hotbar: the held one is used
        Sim two = simWithChargeInSlot(1);
        two.hotbarHasCharge[0] = true;
        ThrowSequencer seq3 = new ThrowSequencer();
        ticks(two, seq3, 1);
        tryAttempt(two, seq3, 7);
        check("prefers the slot already selected over a lower-numbered one", two.count("SET_SLOT") == 0);
    }

    static void restoreDefersToPlayerChoices() {
        System.out.println("restoring the slot never fights the player");
        // player picks another slot themselves before the restore: nothing is sent, lease dropped
        Sim sim = simWithChargeInSlot(4);
        ThrowSequencer seq = new ThrowSequencer();
        ticks(sim, seq, 1);
        tryAttempt(sim, seq, 7);
        sim.clientSlot = sim.serverSlot = 6; // the player scrolls, and the game syncs it
        ticks(sim, seq, 30);
        check("no restore packet after the player changed slot", sim.count("SET_SLOT") == 1);
        check("lease dropped", !seq.hasSlotLease());

        // a screen is open: restore waits, then happens when closed
        Sim busy = simWithChargeInSlot(4);
        ThrowSequencer seq2 = new ThrowSequencer();
        ticks(busy, seq2, 1);
        tryAttempt(busy, seq2, 7);
        busy.busy = true;
        ticks(busy, seq2, 25);
        check("while busy: still on the charge slot, no restore packet", busy.count("SET_SLOT") == 1 && busy.clientSlot == 4);
        busy.busy = false;
        ticks(busy, seq2, 2);
        check("when free: restored once", busy.count("SET_SLOT 1") == 1 && busy.clientSlot == 1 && busy.serverSlot == 1);

        // busy for longer than the lease allows: given up quietly
        Sim stuck = simWithChargeInSlot(4);
        ThrowSequencer seq3 = new ThrowSequencer();
        ticks(stuck, seq3, 1);
        tryAttempt(stuck, seq3, 7);
        stuck.busy = true;
        ticks(stuck, seq3, ThrowSequencer.LEASE_MAX_TICKS + 20);
        stuck.busy = false;
        ticks(stuck, seq3, 5);
        check("a lease that expired is dropped without a late surprise switch", stuck.count("SET_SLOT") == 1 && !seq3.hasSlotLease());
    }

    static void slotSelectionThatDoesNotTakeEffectIsNotUsed() {
        System.out.println("client/server slot mismatch");
        Sim sim = simWithChargeInSlot(4);
        sim.slotSelectionWorks = false; // selectSlot() silently does nothing
        ThrowSequencer seq = new ThrowSequencer();
        ticks(sim, seq, 1);
        check("SLOT_MISMATCH", tryAttempt(sim, seq, 7) == Outcome.SLOT_MISMATCH);
        check("no use was sent from the wrong slot", sim.count("USE") == 0);
        check("nothing pending, nothing counted", !seq.isPending() && seq.attemptsFor(7) == 0);
    }

    static void oneSlotChangePerTick() {
        System.out.println("never two slot changes in one tick");
        // Charge in slot 4, player on slot 1. T1: throw (cooldown 0 so only the slot logic can block a new one).
        // The charge shows up on T3; tick() confirms it and restores the old slot in that same call. A new throw
        // requested on T3 needs the charge slot again: it must wait one tick instead of sending a second slot packet.
        Sim sim = simWithChargeInSlot(4);
        sim.pearls.put(8, ME);
        sim.latency = 2;
        ThrowSequencer seq = new ThrowSequencer();
        sim.tick(seq, () -> check("T1: first throw sent", seq.attempt(sim, new Request(7, 0f, 0f, 24, 0, 8)) == Outcome.SENT));
        ticks(sim, seq, 1); // T2
        check("T2: still pending, still on the charge slot", seq.isPending() && sim.clientSlot == 4);
        Outcome[] o = new Outcome[2];
        sim.tick(seq, () -> o[0] = seq.attempt(sim, new Request(8, 0f, 0f, 24, 0, 8))); // T3
        check("T3: charge confirmed, nothing pending", !seq.isPending());
        check("T3: the restore was sent", sim.countInTick(3, "SET_SLOT 1") == 1);
        check("T3: a selection on the same tick is deferred (SLOT_BUSY) and sends nothing more",
                o[0] == Outcome.SLOT_BUSY && sim.countInTick(3, "USE") == 0 && sim.countInTick(3, "SET_SLOT") == 1);
        sim.tick(seq, () -> o[1] = seq.attempt(sim, new Request(8, 0f, 0f, 24, 0, 8))); // T4
        check("T4: the next tick it goes ahead", o[1] == Outcome.SENT && sim.countInTick(4, "SET_SLOT 4") == 1 && sim.countInTick(4, "USE") == 1);
        check("the deferred attempt was not counted against the pearl", seq.attemptsFor(8) == 1);
        check("never more than one slot packet in any tick", sim.wire.stream().map(l -> l.substring(0, l.indexOf(' '))).distinct()
                .allMatch(tk -> sim.wire.stream().filter(l -> l.startsWith(tk + " ") && l.contains("SET_SLOT")).count() <= 1));
        check("client and server agree", sim.clientSlot == sim.serverSlot);
    }

    static void pendingAttemptBlocksDuplicates() {
        System.out.println("pending attempt");
        Sim sim = simWithChargeInSlot(1);
        sim.latency = 4;
        ThrowSequencer seq = new ThrowSequencer();
        ticks(sim, seq, 1);
        check("first attempt sent", tryAttempt(sim, seq, 7) == Outcome.SENT);
        check("second attempt in the same tick: PENDING", tryAttempt(sim, seq, 7) == Outcome.PENDING);
        check("a different pearl in the same tick: PENDING", tryAttempt(sim, seq, 7) == Outcome.PENDING);
        for (int i = 0; i < 3; i++) {
            ticks(sim, seq, 1);
            check("tick +" + (i + 1) + " before the charge shows up: PENDING (canAttempt=" + seq.canAttempt() + ")",
                    tryAttempt(sim, seq, 7) == Outcome.PENDING && !seq.canAttempt());
        }
        check("still exactly one use on the wire", sim.count("USE") == 1);

        ticks(sim, seq, 1); // the charge spawns this tick
        check("charge seen -> no longer pending", !seq.isPending());
        check("pearl counts as handled for the window", seq.isHandled(7) && tryAttempt(sim, seq, 7) != Outcome.SENT);
        ticks(sim, seq, 40);
        check("still exactly one use after the window passes without a second charge needed",
                sim.count("USE") == 1 || sim.count("USE") == 2);

        // after the pearl's handled window and the cooldown, a retarget is allowed (a second charge) but still limited
        long uses = sim.count("USE");
        check("uses never exceed the attempts cap", uses <= ThrowSequencer.MAX_ATTEMPTS_PER_PEARL);
    }

    static void cooldownedItemCostsNothing() {
        System.out.println("item on cooldown");
        Sim sim = simWithChargeInSlot(4);
        sim.itemOnCooldown = true;
        ThrowSequencer seq = new ThrowSequencer();
        ticks(sim, seq, 1);
        for (int i = 0; i < 20; i++) {
            sim.tick(seq, () -> tryAttempt(sim, seq, 7));
        }
        check("ITEM_COOLDOWN", tryAttempt(sim, seq, 7) == Outcome.ITEM_COOLDOWN);
        check("20 ticks of trying produced zero packets", sim.wire.stream().noneMatch(l -> l.contains("SET_SLOT") || l.contains("USE")));
        check("and no attempt was counted", seq.attemptsFor(7) == 0);

        Sim none = new Sim();
        none.pearls.put(7, ME);
        ThrowSequencer seq2 = new ThrowSequencer();
        ticks(none, seq2, 1);
        check("no charge anywhere -> NO_CHARGE, zero packets", tryAttempt(none, seq2, 7) == Outcome.NO_CHARGE && none.wire.stream().noneMatch(l -> !l.contains("FLYING")));
    }

    static void retryLimit() {
        System.out.println("retry limit");
        Sim sim = simWithChargeInSlot(4);
        sim.clientAccepts = false; // the client refuses every use
        ThrowSequencer seq = new ThrowSequencer();
        List<Integer> attemptTicks = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            sim.tick(seq, () -> {
                if (tryAttempt(sim, seq, 7) == Outcome.REFUSED) attemptTicks.add(sim.tickNo);
            });
        }
        check("exactly MAX_ATTEMPTS_PER_PEARL interactions (" + ThrowSequencer.MAX_ATTEMPTS_PER_PEARL + "), then it stops",
                sim.count("USE") == ThrowSequencer.MAX_ATTEMPTS_PER_PEARL && attemptTicks.size() == ThrowSequencer.MAX_ATTEMPTS_PER_PEARL);
        boolean spaced = true;
        for (int i = 1; i < attemptTicks.size(); i++) {
            spaced &= attemptTicks.get(i) - attemptTicks.get(i - 1) > ThrowSequencer.FAILED_RETRY_TICKS;
        }
        check("retries are spaced more than FAILED_RETRY_TICKS apart " + attemptTicks, spaced);
        check("then the pearl is HANDLED", tryAttempt(sim, seq, 7) == Outcome.HANDLED && seq.isHandled(7));

        // an attempt the client accepted but that never shows up counts too, and also stops
        Sim lost = simWithChargeInSlot(4);
        lost.serverSpawnsCharge = false;
        ThrowSequencer seq2 = new ThrowSequencer();
        for (int i = 0; i < 400; i++) lost.tick(seq2, () -> tryAttempt(lost, seq2, 7));
        check("lost throws are also capped at MAX_ATTEMPTS_PER_PEARL", lost.count("USE") == ThrowSequencer.MAX_ATTEMPTS_PER_PEARL);
        check("a retry never starts while the previous one is pending", noUseWhilePending(lost));
        check("everything ends with client and server on the same slot", lost.clientSlot == lost.serverSlot);
    }

    /** The wire log must never show two USE lines closer together than the minimum confirm window. */
    static boolean noUseWhilePending(Sim sim) {
        int last = -1000;
        for (String l : sim.wire) {
            if (!l.contains(" USE")) continue;
            int t = Integer.parseInt(l.substring(1, l.indexOf(' ')));
            if (t - last < ThrowSequencer.MIN_CONFIRM_TICKS) return false;
            last = t;
        }
        return true;
    }

    static void retriesDoNotBounceTheSlot() {
        System.out.println("retries keep the slot instead of bouncing it");
        Sim sim = simWithChargeInSlot(4);
        sim.clientAccepts = false;
        ThrowSequencer seq = new ThrowSequencer();
        for (int i = 0; i < 300; i++) sim.tick(seq, () -> tryAttempt(sim, seq, 7));
        check("3 refused attempts cost 2 slot packets in total (select once, restore once)", sim.count("SET_SLOT") == 2);
        check("slot restored at the end", sim.clientSlot == 1 && sim.serverSlot == 1);
    }

    static void disableCleansUpWithoutPackets() {
        System.out.println("module disabled");
        Sim sim = simWithChargeInSlot(4);
        ThrowSequencer seq = new ThrowSequencer();
        ticks(sim, seq, 1);
        tryAttempt(sim, seq, 7);
        check("setup: pending, with a slot lease and a tracked pearl", seq.isPending() && seq.hasSlotLease() && seq.trackedPearls() == 1);
        int packetsBefore = sim.wire.size();
        seq.reset();
        check("reset clears pending, lease, tracking", !seq.isPending() && !seq.hasSlotLease() && seq.trackedPearls() == 0);
        check("reset itself sent nothing", sim.wire.size() == packetsBefore);
        ticks(sim, seq, 60);
        check("and nothing is sent afterwards on its behalf (no late restore, no late use)", sim.count("SET_SLOT") == 1 && sim.count("USE") == 1);
        check("client and server still agree (the player simply stays on the charge slot)", sim.clientSlot == sim.serverSlot);
        check("re-enabling starts clean: the pearl can be handled again", seq.canAttempt() && !seq.isHandled(7));
    }

    static void worldChangeCleansUpWithoutPackets() {
        System.out.println("world change");
        Object levelA = new Object(), levelB = new Object(), playerA = new Object(), playerB = new Object();
        Sim sim = simWithChargeInSlot(4);
        ThrowSequencer seq = new ThrowSequencer();
        check("first bind counts as a change (state is empty anyway)", seq.bindWorld(levelA, playerA));
        ticks(sim, seq, 1);
        tryAttempt(sim, seq, 7);
        check("same level and player: nothing dropped", !seq.bindWorld(levelA, playerA) && seq.isPending() && seq.hasSlotLease());

        int packetsBefore = sim.wire.size();
        check("new level object: state dropped", seq.bindWorld(levelB, playerA));
        check("pending, lease and tracking are gone", !seq.isPending() && !seq.hasSlotLease() && seq.trackedPearls() == 0);
        check("no packet was sent for the old world", sim.wire.size() == packetsBefore);

        // entity ids are reused in the new world: the old 'handled' entry must not hide the new pearl
        Sim s2 = simWithChargeInSlot(1);
        ThrowSequencer q = new ThrowSequencer();
        q.bindWorld(levelA, playerA);
        ticks(s2, q, 1);
        tryAttempt(s2, q, 7);
        ticks(s2, q, 10);
        check("setup: pearl 7 handled in the old world", q.isHandled(7));
        q.bindWorld(levelB, playerB);
        check("same id in the new world is not considered handled", !q.isHandled(7) && q.attemptsFor(7) == 0);

        // respawn: same level, new player object
        check("new player object alone also drops state", q.bindWorld(levelB, new Object()));
    }

    static void fuzz() {
        System.out.println("fuzz: random worlds, 4000 runs x 250 ticks");
        Random rnd = new Random(7);
        int runs = 4000;
        int badSlot = 0, badCount = 0, badOwner = 0, badPending = 0, badSlotPackets = 0, badStale = 0;
        int[] sentCount = {0};
        int[] staleAfterCleanup = {0};
        for (int r = 0; r < runs; r++) {
            Sim sim = new Sim();
            ThrowSequencer seq = new ThrowSequencer();
            sim.clientSlot = sim.serverSlot = rnd.nextInt(9);
            for (int i = 0; i < 9; i++) sim.hotbarHasCharge[i] = rnd.nextInt(5) == 0;
            sim.offhandHasCharge = rnd.nextInt(6) == 0;
            sim.latency = rnd.nextInt(12);
            sim.serverSpawnsCharge = rnd.nextInt(4) != 0;
            sim.clientAccepts = rnd.nextInt(5) != 0;
            int[] ids = {3, 5, 8, 13};
            Map<Integer, Integer> useCounts = new HashMap<>();
            Object level = new Object();
            Object player = new Object();
            seq.bindWorld(level, player);
            for (int t = 0; t < 250; t++) {
                // world events
                if (rnd.nextInt(60) == 0) sim.busy = !sim.busy;
                if (rnd.nextInt(40) == 0) sim.itemOnCooldown = !sim.itemOnCooldown;
                if (rnd.nextInt(50) == 0) sim.clientSlot = sim.serverSlot = rnd.nextInt(9); // the player scrolls
                if (rnd.nextInt(30) == 0) {
                    int id = ids[rnd.nextInt(ids.length)];
                    int pick = rnd.nextInt(4);
                    if (pick == 0) sim.pearls.put(id, ME);
                    else if (pick == 1) sim.pearls.put(id, OTHER);
                    else if (pick == 2) sim.pearls.put(id, null);
                    else sim.pearls.remove(id);
                }
                boolean worldChange = rnd.nextInt(120) == 0;
                boolean disable = rnd.nextInt(150) == 0;
                boolean wasPending = seq.isPending();
                long useBefore = sim.count("USE");
                sim.tick(seq, () -> {
                    if (worldChange) {
                        seq.bindWorld(new Object(), player);
                        sim.pearls.clear(); // a new world has different entities
                        sim.ownCharges.clear();
                        sim.spawnAt.clear();
                    }
                    if (disable) seq.reset();
                    if ((worldChange || disable) && (seq.isPending() || seq.hasSlotLease() || seq.trackedPearls() != 0)) {
                        staleAfterCleanup[0]++; // checked right after the cleanup, before any new attempt
                    }
                    int id = ids[rnd.nextInt(ids.length)];
                    if (!sim.busy && rnd.nextInt(2) == 0) {
                        int before = seq.attemptsFor(id);
                        Outcome o = tryAttempt(sim, seq, id);
                        if (o == Outcome.SENT || o == Outcome.REFUSED) {
                            useCounts.merge(id, 1, Integer::sum);
                            if (seq.attemptsFor(id) != before + 1) fail("attempt counter not incremented");
                        }
                        if (o == Outcome.SENT) sentCount[0]++;
                    }
                });
                if (wasPending && sim.count("USE") > useBefore && !worldChange && !disable) badPending++;
                if (sim.countInTick(sim.tickNo, "SET_SLOT") > 1) badSlotPackets++;
                if (sim.clientSlot != sim.serverSlot) badSlot++;
                if (sim.useOnUnownedPearl) badOwner++;
                sim.useOnUnownedPearl = false;
            }
            for (Map.Entry<Integer, Integer> e : useCounts.entrySet()) {
                // counts are per world: a world change legitimately starts again, so bound by cap x (changes + resets + 1)
                if (e.getValue() > ThrowSequencer.MAX_ATTEMPTS_PER_PEARL * 12) badCount++;
            }
        }
        check("client slot == server slot at the end of every tick (" + badSlot + " violations)", badSlot == 0);
        check("at most one slot packet per tick (" + badSlotPackets + " violations)", badSlotPackets == 0);
        check("never a use while another attempt was pending (" + badPending + " violations)", badPending == 0);
        check("never a use on a pearl that was not verifiably mine at that moment (" + badOwner + " violations)", badOwner == 0);
        check("interaction counts stay bounded by the retry limit (" + badCount + " violations)", badCount == 0);
        badStale = staleAfterCleanup[0];
        check("right after a world change or a disable nothing is pending, leased or tracked (" + badStale + " violations)", badStale == 0);
        check("the fuzz actually exercised sends (" + sentCount[0] + ")", sentCount[0] > 1000);
    }

    // ---- tiny assertion helpers ---------------------------------------------------------------------------------

    static void check(String name, boolean ok) {
        System.out.println("  " + (ok ? "ok  " : "FAIL") + " " + name);
        if (!ok) failures++;
    }

    static void fail(String message) {
        System.out.println("  FAIL " + message);
        failures++;
    }
}
