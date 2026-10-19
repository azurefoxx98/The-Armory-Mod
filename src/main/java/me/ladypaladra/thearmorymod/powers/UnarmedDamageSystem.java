package me.ladypaladra.thearmorymod.powers;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
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
 * Makes a bare-handed punch hit harder while the puncher wears gear carrying the unarmed damage
 * stat.
 *
 * <p>No armour field can do this on its own. Armour can raise a damage cause, which would also
 * raise every sword, or a damage class, and a punch shares its class with longswords, spears,
 * axes and guard bashes. So a punch is recognised by what makes it one: a primary attack, from
 * the player directly rather than a projectile or an effect, of plain Physical damage, with
 * nothing in the main hand. Holding a block or a torch turns the click into a different swing,
 * so an empty hand is the rule. A shield in the off-hand does not count, because the main hand
 * is the hotbar slot.</p>
 *
 * <p>The primary check also keeps a Mirror Shield reflection out. A reflection is dealt by the
 * shield holder directly and is Physical underneath, but it never comes from an attack click.</p>
 */
public final class UnarmedDamageSystem extends DamageEventSystem {

    private static final String PHYSICAL = "Physical";

    private final LazyStatIndex unarmedDamageIndex = new LazyStatIndex(ArmoryStatIds.UNARMED_DAMAGE);

    private int physicalIndex = AssetMapWithIndexes.NOT_FOUND;

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> chunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage
    ) {
        if (!isPrimaryDirectHit(damage) || damage.getDamageCauseIndex() != physicalIndex()) {
            return;
        }

        Ref<EntityStore> attackerRef = ((Damage.EntitySource) damage.getSource()).getRef();
        if (attackerRef == null || !attackerRef.isValid() || attackerRef.equals(chunk.getReferenceTo(index))) {
            return;
        }

        if (commandBuffer.getComponent(attackerRef, Player.getComponentType()) == null) {
            return;
        }

        ItemStack held = InventoryComponent.getItemInHand(commandBuffer, attackerRef);
        if (held != null && !held.isEmpty()) {
            return;
        }

        EntityStatMap stats = commandBuffer.getComponent(attackerRef, EntityStatMap.getComponentType());
        float multiplier = StatMultiplier.forStat(stats, unarmedDamageIndex.get());
        if (multiplier <= 1.0F + StatMultiplier.EPSILON) {
            return;
        }

        damage.setAmount(damage.getAmount() * multiplier);
    }

    /**
     * Answers whether a hit came from an entity's own attack click: not cancelled, not already
     * nothing, dealt by the entity rather than its projectile or an effect, and tagged by the
     * engine as a primary attack.
     */
    static boolean isPrimaryDirectHit(@Nonnull Damage damage) {
        Damage.Source source = damage.getSource();
        return !damage.isCancelled()
                && damage.getAmount() > 0.0F
                && source instanceof Damage.EntitySource
                && !(source instanceof Damage.ProjectileSource)
                && !(source instanceof Damage.EntityEffectSource)
                && damage.getMetaStore().getIfPresentMetaObject(Damage.INTERACTION_TYPE) == InteractionType.Primary;
    }

    private int physicalIndex() {
        if (physicalIndex == AssetMapWithIndexes.NOT_FOUND) {
            physicalIndex = DamageCause.getAssetMap().getIndex(PHYSICAL);
        }
        return physicalIndex;
    }
}
