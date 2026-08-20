package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class ArmoryStatService {

    private static final float EPSILON = 0.0001f;

    private volatile int arrowDamageBonusIdx = Integer.MIN_VALUE;

    public float getArrowDamageBonus(Store<EntityStore> store,
                                     CommandBuffer<EntityStore> cb,
                                     Ref<EntityStore> ownerRef) {
        int statIndex = resolveArrowDamageBonusIdx();
        if (statIndex == Integer.MIN_VALUE) return 1.0f;

        EntityStatMap map = cb != null ? cb.getComponent(ownerRef, EntityStatMap.getComponentType()) : null;
        if (map == null) map = store.getComponent(ownerRef, EntityStatMap.getComponentType());
        if (map == null) return 1.0f;

        EntityStatValue value = map.get(statIndex);

        if (value == null) {
            map.update();
            value = map.get(statIndex);
            if (value == null) return 1.0f;
        }

        EntityStatType statType = EntityStatType.getAssetMap().getAsset(statIndex);
        if (statType == null) return 1.0f;

        float baseMax = statType.getMax();
        float effectiveMax = value.getMax();

        if (baseMax <= 0.0f) return 1.0f;

        float multiplier;
        // An unmodified stat has matching maxima, though modifiers totaling 1.0 are indistinguishable.
        if (Math.abs(effectiveMax - baseMax) <= EPSILON) {
            multiplier = 1.0f;
        } else {
            // The extra 1.0 looks suspicious and has twice been mistaken for double counting.
            // Engine Multiplicative math is value * amount rather than value * (1 + amount).
            // An item amount of 0.2 makes the effective maximum baseMax * 0.2.
            // Dividing by baseMax recovers that 0.2, and 1.0 + 0.2 means 20 percent more.
            // Amounts are summed before multiplication, so two 0.15 items recover 0.30.
            // Plain effectiveMax / baseMax would shrink every bonus to a fraction of itself.
            float authoredAmount = effectiveMax / baseMax;
            multiplier = 1.0f + authoredAmount;
        }

        return multiplier;
    }

    private int resolveArrowDamageBonusIdx() {
        int idx = arrowDamageBonusIdx;
        if (idx != Integer.MIN_VALUE) {
            return idx;
        }

        int found = EntityStatType.getAssetMap().getIndex(ArmoryStatIds.ARROW_DAMAGE_BONUS);
        if (found != Integer.MIN_VALUE) {
            arrowDamageBonusIdx = found;
            return found;
        }

        return Integer.MIN_VALUE;
    }
}
