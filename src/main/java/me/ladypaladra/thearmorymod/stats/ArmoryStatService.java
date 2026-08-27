package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class ArmoryStatService {

    private final LazyStatIndex arrowDamageBonusIndex =
            new LazyStatIndex(ArmoryStatIds.ARROW_DAMAGE_BONUS);

    public float getArrowDamageBonus(Store<EntityStore> store,
                                     CommandBuffer<EntityStore> cb,
                                     Ref<EntityStore> ownerRef) {
        EntityStatMap map = cb != null ? cb.getComponent(ownerRef, EntityStatMap.getComponentType()) : null;
        if (map == null) map = store.getComponent(ownerRef, EntityStatMap.getComponentType());
        return StatMultiplier.forStat(map, arrowDamageBonusIndex.get());
    }
}
