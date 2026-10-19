package me.ladypaladra.thearmorymod.powers;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.stats.ArmoryStatIds;
import me.ladypaladra.thearmorymod.stats.LazyStatIndex;
import me.ladypaladra.thearmorymod.stats.StatMultiplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reduces fire damage by the fire resistance stat and explosion damage by the blast resistance
 * stat.
 *
 * <p>Both are read from stats rather than from armour resistances on purpose. Fire resistance
 * comes from a shield, and the engine applies a held item's stat modifiers from the main hand
 * or, when the main hand allows it, from the off-hand, so a stat is how a carried item can
 * resist anything. Explosions deal the Environment cause, which bypasses every armour
 * resistance in the engine, so armour data alone could never soften a blast.</p>
 *
 * <p>Blast resistance matches Environment exactly. Drowning inherits from it and must not be
 * reduced by a bomb pack.</p>
 */
public final class StatResistanceSystem extends DamageEventSystem {

    private static final String FIRE = "Fire";

    private static final String ENVIRONMENT = "Environment";

    private final LazyStatIndex fireResistanceIndex = new LazyStatIndex(ArmoryStatIds.FIRE_RESISTANCE);

    private final LazyStatIndex blastResistanceIndex = new LazyStatIndex(ArmoryStatIds.BLAST_RESISTANCE);

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return EntityStatMap.getComponentType();
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> chunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage
    ) {
        if (damage.isCancelled() || damage.getAmount() <= 0.0F) {
            return;
        }

        DamageCause cause = DamageCause.getAssetMap().getAsset(damage.getDamageCauseIndex());
        if (cause == null) {
            return;
        }

        int statIndex;
        DamageCause fire = DamageCause.getAssetMap().getAsset(FIRE);
        if (fire != null && cause.isOrInherits(fire)) {
            statIndex = fireResistanceIndex.get();
        } else if (ENVIRONMENT.equals(cause.getId())) {
            statIndex = blastResistanceIndex.get();
        } else {
            return;
        }

        EntityStatMap statMap = chunk.getComponent(index, EntityStatMap.getComponentType());
        float resistance = StatMultiplier.forStat(statMap, statIndex) - 1.0F;
        if (resistance <= StatMultiplier.EPSILON) {
            return;
        }

        damage.setAmount(damage.getAmount() * (1.0F - Math.min(resistance, 1.0F)));
    }
}
