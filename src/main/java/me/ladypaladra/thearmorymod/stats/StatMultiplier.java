package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;

import javax.annotation.Nullable;

public final class StatMultiplier {

    /**
     * Tolerance for comparing two floats that are expected to be equal. This is shared so
     * callers cannot drift toward different ideas of equality.
     */
    public static final float EPSILON = 0.0001F;

    private StatMultiplier() {
    }

    /**
     * Recovers the multiplier represented by an entity stat's base and effective maxima.
     *
     * <p>The engine multiplies the base maximum by the sum of authored multiplicative amounts.
     * Dividing the effective maximum by the base maximum recovers that sum, then adding 1.0
     * turns an authored amount such as 0.2 into the intended 1.2 multiplier. This inference
     * depends on every modifier for the stat being multiplicative.</p>
     *
     * <p>Authored amounts are plain floats and may be negative. An amount of -0.3 is a useful
     * debuff with a multiplier of 0.7, so clamping at 1.0 would silently make slowing armour
     * impossible. The result is clamped at 0.0 instead because a negative multiplier has no
     * useful meaning. It would make an arrow heal its target or push a jumping player into the
     * ground. Current assets only author positive amounts, so this does not change their
     * behaviour.</p>
     *
     * <p>A multiplicative sum of exactly 1.0 makes the effective maximum equal the base maximum.
     * It is indistinguishable from an unmodified stat and therefore reads as 1.0.</p>
     *
     * <p>A result that is not finite is treated as unreadable and reads as 1.0. An authored
     * amount is a plain float, so a number too large for one, such as a mistyped run of zeroes,
     * parses to infinity rather than being rejected. Callers write this value straight into live
     * jump force, sprint speed and arrow damage, where an infinite or undefined multiplier does
     * far more damage than no bonus at all.</p>
     *
     * @param baseMax the stat type's base maximum
     * @param effectiveMax the entity stat's effective maximum
     * @return the multiplier represented by the maxima
     */
    public static float fromMaxima(float baseMax, float effectiveMax) {
        if (baseMax <= 0.0F) {
            return 1.0F;
        }

        if (Math.abs(effectiveMax - baseMax) <= EPSILON) {
            return 1.0F;
        }

        float multiplier = 1.0F + effectiveMax / baseMax;
        if (!Float.isFinite(multiplier)) {
            return 1.0F;
        }

        return Math.max(0.0F, multiplier);
    }

    /**
     * Reads a stat multiplier while allowing an asset registered after the map was built to
     * become visible through one map update.
     *
     * @param statMap the entity's stat map
     * @param statIndex the resolved stat asset index
     * @return the represented multiplier, or 1.0 when the stat cannot be read
     */
    public static float forStat(@Nullable EntityStatMap statMap, int statIndex) {
        if (statMap == null || statIndex == AssetMapWithIndexes.NOT_FOUND) {
            return 1.0F;
        }

        EntityStatValue value = statMap.get(statIndex);
        if (value == null) {
            statMap.update();
            value = statMap.get(statIndex);
        }
        if (value == null) {
            return 1.0F;
        }

        EntityStatType statType = EntityStatType.getAssetMap().getAsset(statIndex);
        if (statType == null) {
            return 1.0F;
        }

        return fromMaxima(statType.getMax(), value.getMax());
    }
}
