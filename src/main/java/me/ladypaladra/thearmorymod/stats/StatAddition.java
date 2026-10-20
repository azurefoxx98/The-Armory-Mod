package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;

import javax.annotation.Nullable;

/**
 * Reads the amount carried by a stat whose worn modifiers are all Additive.
 *
 * <p>The engine adds every Additive amount to the stat type's base maximum, so the effective
 * maximum minus the base maximum is the sum of what the worn pieces declare. The stat assets that
 * use this start at a maximum of 0, the same shape vanilla gives Ammo and MagicCharges.</p>
 */
public final class StatAddition {

    private StatAddition() {
    }

    /**
     * Recovers the summed amount from a stat's base and effective maxima.
     *
     * <p>A negative amount is kept, so a piece can carry a penalty. A result that is not finite
     * reads as 0, because a mistyped amount too large for a float parses to infinity, and the
     * caller adds this straight onto a player's health or mana.</p>
     *
     * @param baseMax the stat type's base maximum
     * @param effectiveMax the entity stat's effective maximum
     * @return the summed amount, or 0 when the stat is unmodified or unreadable
     */
    public static float fromMaxima(float baseMax, float effectiveMax) {
        float amount = effectiveMax - baseMax;
        if (!Float.isFinite(amount) || Math.abs(amount) <= StatMultiplier.EPSILON) {
            return 0.0F;
        }
        return amount;
    }

    /**
     * Reads a stat's summed amount while allowing an asset registered after the map was built to
     * become visible through one map update.
     *
     * @param statMap the entity's stat map
     * @param statIndex the resolved stat asset index
     * @return the summed amount, or 0 when the stat cannot be read
     */
    public static float forStat(@Nullable EntityStatMap statMap, int statIndex) {
        if (statMap == null || statIndex == AssetMapWithIndexes.NOT_FOUND) {
            return 0.0F;
        }

        EntityStatValue value = statMap.get(statIndex);
        if (value == null) {
            statMap.update();
            value = statMap.get(statIndex);
        }
        if (value == null) {
            return 0.0F;
        }

        EntityStatType statType = EntityStatType.getAssetMap().getAsset(statIndex);
        if (statType == null) {
            return 0.0F;
        }

        return fromMaxima(statType.getMax(), value.getMax());
    }
}
