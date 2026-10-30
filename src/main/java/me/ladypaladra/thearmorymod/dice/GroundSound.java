package me.ladypaladra.thearmorymod.dice;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * How a die striking the ground sounds and puffs, from the struck block's own sound set: planks knock, stone clacks,
 * sand and grass thud softly, each with the game's own sound for that material.
 *
 * <p>A block's sound set offers a strike sound (its Hit, a tool striking it) and a landing sound (its Land, a body
 * landing on it). Hard materials ring when struck, so a die on them plays the strike; soft ones only give, so a die on
 * them plays the landing. A set that lacks the preferred one plays the other.</p>
 */
final class GroundSound {

    /**
     * The game's sound sets of yielding materials: a die landing on them is muffled, so it plays their landing sound.
     * Every other set rings, and plays its strike sound.
     */
    static final Set<String> SOFT = Set.of("Sand", "Snow", "Dirt", "Grass", "Gravel", "Mud", "Poop", "Soft", "Cloth",
            "Plushie", "LeavesGround", "Cocoon", "Eggsac", "Rope", "Default");

    /**
     * A strike at least this hard, in blocks per second into the surface, raises the block's own dust, and at least
     * this hard raises its heavy landing dust instead of a tap's.
     */
    static final double DUST_SPEED = 2.0;
    static final double HEAVY_SPEED = 5.5;

    /**
     * A die entering water at least this fast throws the heavy splash.
     */
    static final double HEAVY_SPLASH = 6.0;

    enum Kind {
        HIT,
        LAND
    }

    private GroundSound() {
    }

    /**
     * Which of the block's sounds a die striking it plays, or null when the set has neither.
     */
    @Nullable
    static Kind kind(@Nonnull String soundSet, boolean hasHit, boolean hasLand) {
        if (SOFT.contains(soundSet)) {
            return hasLand ? Kind.LAND : hasHit ? Kind.HIT : null;
        }
        return hasHit ? Kind.HIT : hasLand ? Kind.LAND : null;
    }

    /**
     * How loud a strike is, from how hard the die came in: a light tap is barely heard, a hard throw is clear.
     */
    static float volume(double struck) {
        return (float) Math.max(0.15, Math.min(0.8, struck / 7.0));
    }

    /**
     * The pitch a block's sound is played at. Its sounds are made for a player's weight and a tool's swing, so a small
     * die plays them higher, a landing a little higher still, with a small random spread so no two sound alike.
     */
    static float pitch(@Nonnull Kind kind, @Nonnull RandomGenerator rng) {
        float base = kind == Kind.LAND ? 1.45f : 1.3f;
        return base + rng.nextFloat() * 0.3f;
    }

    /**
     * Whether a strike raises the block's dust at all, and whether it is the heavy landing dust.
     */
    static boolean dusts(double struck) {
        return struck >= DUST_SPEED;
    }

    static boolean heavy(double struck) {
        return struck >= HEAVY_SPEED;
    }

    /**
     * How loud a die's splash is, from how fast it fell into the water.
     */
    static float splashVolume(double entrySpeed) {
        return (float) Math.max(0.25, Math.min(1.0, entrySpeed / 10.0));
    }
}
