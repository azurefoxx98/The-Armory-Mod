package me.ladypaladra.thearmorymod.powers;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Decides which landing effects fire, from what is worn and how far the wearer fell.
 *
 * <p>A worn piece names the effect it carries with the item tag {@code "ArmoryOnLanding"}, whose
 * values are root interaction ids. The effect's own root interaction holds its settings as tags,
 * so everything about one effect lives in one file: {@code "ArmoryLandingMinFall"} is the
 * shortest fall in blocks that sets it off, and {@code "ArmoryLandingPieces"} is how many worn
 * pieces must name it. Pieces of different items naming the same effect count together, which
 * is how a recoloured piece completes its set.</p>
 *
 * <p>An effect has two halves, both ordinary root interactions. The root itself runs on the wearer
 * and is what the wearer and those nearby see and hear. {@code "ArmoryLandingHit"} names a second
 * root that runs once for every creature within {@code "ArmoryLandingRadius"} blocks, with that
 * creature as its target, the way a selector's {@code HitEntity} does. The server finds those
 * creatures itself because the engine's selectors wait for the player's own game to report its
 * hits, and nothing guarantees a player's game runs a chain the server started.</p>
 *
 * <p>A longer fall can hit harder. Both halves carry the ability attribute {@link #FALL_ATTRIBUTE},
 * which is 1 at the shortest fall and grows by {@code "ArmoryLandingScalePerBlock"} for each block
 * beyond it, up to {@code "ArmoryLandingMaxScale"}. A damage interaction opts in with the engine's
 * own field, {@code "DamageAttribute": "ArmoryFall"}. Anything that does not name it is unchanged.</p>
 *
 * <p>{@code "ArmoryLandingRecharge"} is how many seconds must pass before the same wearer can set the
 * effect off again, and it holds back both halves together. The engine's own Cooldown field on the
 * root would hold back only the half on the wearer, so it is not the place for it.</p>
 *
 * <p>Imports nothing from the engine, so every rule here is unit tested.</p>
 */
public final class LandingTrigger {

    public static final String MIN_FALL_TAG = "ArmoryLandingMinFall";

    public static final String PIECES_TAG = "ArmoryLandingPieces";

    public static final String SCALE_PER_BLOCK_TAG = "ArmoryLandingScalePerBlock";

    public static final String MAX_SCALE_TAG = "ArmoryLandingMaxScale";

    public static final String RADIUS_TAG = "ArmoryLandingRadius";

    public static final String HIT_TAG = "ArmoryLandingHit";

    public static final String RECHARGE_TAG = "ArmoryLandingRecharge";

    public static final String FALL_ATTRIBUTE = "ArmoryFall";

    /**
     * The fall at which the game itself starts to hurt, a little under seven blocks. A standing jump
     * comes down from about two, so an ordinary jump never counts.
     */
    public static final double DEFAULT_MIN_FALL = 7.0;

    public static final int DEFAULT_PIECES = 1;

    public static final double DEFAULT_SCALE_PER_BLOCK = 0.1;

    public static final double DEFAULT_MAX_SCALE = 3.0;

    public static final double DEFAULT_RADIUS = 4.0;

    public static final double DEFAULT_RECHARGE = 0.0;

    private LandingTrigger() {
    }

    /**
     * One effect's settings, read from the tags of its root interaction.
     */
    public record Settings(double minFall, int pieces, double scalePerBlock, double maxScale, double radius, @Nullable String hit, double recharge) {

        public static final Settings DEFAULT = new Settings(DEFAULT_MIN_FALL, DEFAULT_PIECES, DEFAULT_SCALE_PER_BLOCK, DEFAULT_MAX_SCALE, DEFAULT_RADIUS, null, DEFAULT_RECHARGE);

        /**
         * Reads the settings. A missing tag takes its default. A value that is not a usable number
         * also takes its default, and is reported through {@code problems} so a typo is never
         * silent.
         */
        @Nonnull
        public static Settings read(@Nullable Map<String, String[]> tags, @Nonnull Consumer<String> problems) {
            if (tags == null) {
                return DEFAULT;
            }

            double minFall = DEFAULT_MIN_FALL;
            String minFallText = single(tags.get(MIN_FALL_TAG));
            if (minFallText != null) {
                Double value = parseNumber(minFallText);
                if (value != null && value >= 0.0) {
                    minFall = value;
                } else {
                    problems.accept(MIN_FALL_TAG + " is \"" + minFallText + "\", which is not a number of blocks of 0 or more");
                }
            }

            int pieces = DEFAULT_PIECES;
            String piecesText = single(tags.get(PIECES_TAG));
            if (piecesText != null) {
                Double value = parseNumber(piecesText);
                if (value != null && value >= 1.0 && value == Math.rint(value)) {
                    pieces = value.intValue();
                } else {
                    problems.accept(PIECES_TAG + " is \"" + piecesText + "\", which is not a whole number of 1 or more");
                }
            }

            double scalePerBlock = DEFAULT_SCALE_PER_BLOCK;
            String scaleText = single(tags.get(SCALE_PER_BLOCK_TAG));
            if (scaleText != null) {
                Double value = parseNumber(scaleText);
                if (value != null && value >= 0.0) {
                    scalePerBlock = value;
                } else {
                    problems.accept(SCALE_PER_BLOCK_TAG + " is \"" + scaleText + "\", which is not a number of 0 or more");
                }
            }

            double maxScale = DEFAULT_MAX_SCALE;
            String maxText = single(tags.get(MAX_SCALE_TAG));
            if (maxText != null) {
                Double value = parseNumber(maxText);
                if (value != null && value >= 1.0) {
                    maxScale = value;
                } else {
                    problems.accept(MAX_SCALE_TAG + " is \"" + maxText + "\", which is not a number of 1 or more");
                }
            }

            double radius = DEFAULT_RADIUS;
            String radiusText = single(tags.get(RADIUS_TAG));
            if (radiusText != null) {
                Double value = parseNumber(radiusText);
                if (value != null && value > 0.0) {
                    radius = value;
                } else {
                    problems.accept(RADIUS_TAG + " is \"" + radiusText + "\", which is not a number of blocks above 0");
                }
            }

            String hit = single(tags.get(HIT_TAG));
            if (hit != null && hit.isEmpty()) {
                hit = null;
            }

            double recharge = DEFAULT_RECHARGE;
            String rechargeText = single(tags.get(RECHARGE_TAG));
            if (rechargeText != null) {
                Double value = parseNumber(rechargeText);
                if (value != null && value >= 0.0) {
                    recharge = value;
                } else {
                    problems.accept(RECHARGE_TAG + " is \"" + rechargeText + "\", which is not a number of seconds of 0 or more");
                }
            }

            return new Settings(minFall, pieces, scalePerBlock, maxScale, radius, hit, recharge);
        }

        public boolean firesFor(int wornPieces, double fallDistance) {
            return wornPieces >= pieces && fallDistance >= minFall;
        }

        /**
         * Whether the recharge since the last time this effect fired for the same wearer is over.
         *
         * @param lastFiredMs when it last fired, or null if it never has
         */
        public boolean recharged(@Nullable Long lastFiredMs, long nowMs) {
            return lastFiredMs == null || nowMs - lastFiredMs >= recharge * 1000.0;
        }

        /**
         * The value of {@link #FALL_ATTRIBUTE} for a fall: 1 at the shortest fall that fires, and
         * never below 1 or above the maximum.
         */
        public float scaleFor(double fallDistance) {
            double scale = 1.0 + Math.max(0.0, fallDistance - minFall) * scalePerBlock;
            return (float) Math.min(maxScale, scale);
        }
    }

    /**
     * Counts, for each effect, how many worn pieces name it. A piece naming the same effect twice
     * counts once. The order is the order the effects were first met, so a test can rely on it.
     *
     * @param wornTagValues the {@code ArmoryOnLanding} values of each worn piece, null for a piece
     *                      without the tag
     */
    @Nonnull
    public static Map<String, Integer> countPieces(@Nonnull List<String[]> wornTagValues) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String[] values : wornTagValues) {
            if (values == null) {
                continue;
            }
            Set<String> named = new LinkedHashSet<>();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    named.add(value.trim());
                }
            }
            for (String id : named) {
                counts.merge(id, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * The effects that fire for one landing, each with its settings, in the order of {@code counts}.
     *
     * @param counts       worn pieces per effect, from {@link #countPieces}
     * @param settings     the settings of an effect, or null when the effect does not exist
     * @param fallDistance the blocks fallen, as the engine measured them
     */
    @Nonnull
    public static Map<String, Settings> firing(
            @Nonnull Map<String, Integer> counts,
            @Nonnull Function<String, Settings> settings,
            double fallDistance
    ) {
        Map<String, Settings> fired = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            Settings effect = settings.apply(entry.getKey());
            if (effect != null && effect.firesFor(entry.getValue(), fallDistance)) {
                fired.put(entry.getKey(), effect);
            }
        }
        return fired;
    }

    @Nullable
    private static String single(@Nullable String[] values) {
        if (values == null || values.length == 0 || values[0] == null) {
            return null;
        }
        return values[0].trim();
    }

    @Nullable
    private static Double parseNumber(@Nonnull String text) {
        try {
            double value = Double.parseDouble(text);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
