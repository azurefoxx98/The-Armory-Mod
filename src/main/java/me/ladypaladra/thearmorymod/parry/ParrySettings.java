package me.ladypaladra.thearmorymod.parry;

/**
 * Hardcoded parry tuning + weapon allowlist.
 *
 * <p>Edit this class when the client wants to enable or disable parry for weapons.</p>
 */
public final class ParrySettings {

    private ParrySettings() {
    }

    /** Time the player has, in ms, after starting a block to land a perfect parry. */
    public static final long PARRY_WINDOW_MS = 400L;

    /** Prevents block-spam from instantly reopening the parry window every frame. */
    public static final long BLOCK_SPAM_COOLDOWN_MS = 200L;

    /** Requires the entity to still be blocking when the hit arrives. */
    public static final boolean REQUIRE_BLOCKING = true;

    /**
     * How long the parried attacker is stunned, in seconds. This is the only value that
     * decides that, and it is counted down by the stun component.
     *
     * <p>The stun effect asset carries a duration of its own, which is a different number
     * answering a different question. That one says how long a single application of the
     * particle and movement lock survives, and the stun system re-applies it every tick and
     * removes it when this countdown reaches zero. Raising this value lengthens the stun.
     * Raising the asset's value does not.</p>
     */
    public static final float STUN_DURATION_SECONDS = 1F;
}
