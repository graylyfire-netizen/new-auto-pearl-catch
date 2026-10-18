package me.alpha432.oyvey.util.interaction;

import java.util.UUID;

/**
 * Decides whether an entity is an ender pearl that the local player verifiably threw.
 *
 * <p>It has no Minecraft imports on purpose: the module reads the entity into a {@link Snapshot} and this class
 * makes the decision, so the same rule is used for target filtering and for the re-check made right before each
 * interaction, and it can be tested without the game.
 *
 * <p>Ownership is only accepted when the owner is <em>known</em>. On the client a projectile's owner is resolved
 * from the spawn packet, so a pearl whose owner is not (yet) resolved reports {@code null}; that is
 * {@link Verdict#UNVERIFIED}, never "assume it is mine".
 */
public final class PearlOwnership {
    private PearlOwnership() {
        throw new AssertionError();
    }

    public enum Verdict {
        /** A living ender pearl whose resolved owner is the local player. The only verdict that may be acted on. */
        LOCAL,
        /** A living ender pearl with a resolved owner who is somebody else. */
        OTHER,
        /** A living ender pearl whose owner is unresolved, or there is no local player to compare against. */
        UNVERIFIED,
        /** Not an ender pearl, or already removed. */
        NOT_A_LIVE_PEARL;

        public boolean isLocal() {
            return this == LOCAL;
        }
    }

    /**
     * @param enderPearl           the entity type is an ender pearl
     * @param alive                the entity has not been removed
     * @param ownerUuid            UUID of the resolved owner, or {@code null} if the owner is unresolved
     * @param ownerIsLocalInstance the resolved owner is the very same client-side player object as the local player
     *                             (so a stale player object from before a respawn or world change never matches)
     */
    public record Snapshot(boolean enderPearl, boolean alive, UUID ownerUuid, boolean ownerIsLocalInstance) {
    }

    public static Verdict classify(Snapshot pearl, UUID localPlayerUuid) {
        if (pearl == null || !pearl.enderPearl() || !pearl.alive()) return Verdict.NOT_A_LIVE_PEARL;
        if (localPlayerUuid == null || pearl.ownerUuid() == null) return Verdict.UNVERIFIED;
        // both the identity and the UUID must agree; either one alone is not enough
        if (pearl.ownerIsLocalInstance() && pearl.ownerUuid().equals(localPlayerUuid)) return Verdict.LOCAL;
        return Verdict.OTHER;
    }
}
