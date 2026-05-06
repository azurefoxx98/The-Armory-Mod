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
        if (Math.abs(effectiveMax - baseMax) <= EPSILON) {
            multiplier = 1.0f;
        } else {
            float bonusFraction = effectiveMax / baseMax;
            multiplier = 1.0f + bonusFraction;
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