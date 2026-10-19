package me.ladypaladra.thearmorymod.powers;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fires the Boom Shroom Pack's spore burst when a hit drops its wearer below a third of their
 * health.
 *
 * <p>It runs in the inspect phase, where each hit's amount is final but not yet taken from
 * health, so the health before and after the hit are both known. The burst fires on the hit that
 * crosses the line, then waits out its recharge. It pushes nearby creatures away and does
 * nothing else: no damage is cancelled and no health is given, so a lethal hit still kills.</p>
 *
 * <p>The recharge is kept per player id rather than on the entity, so two hits landing in the
 * same tick cannot both fire it before a component would have been written.</p>
 */
public final class SporeBurstSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public static final String BURST_INTERACTION_ID = "Armory_Spore_Burst";

    public static final long RECHARGE_MS = 60_000L;

    private final Map<UUID, Long> lastBurstMs = new ConcurrentHashMap<>();

    private boolean reportedMissing;

    private final Query<EntityStore> query = Archetype.of(
            PlayerRef.getComponentType(),
            EntityStatMap.getComponentType(),
            InventoryComponent.Armor.getComponentType()
    );

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getInspectDamageGroup();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
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

        if (!WornTags.wears(chunk.getComponent(index, InventoryComponent.Armor.getComponentType()), WornTags.SPORE_BURST)) {
            return;
        }

        EntityStatMap statMap = chunk.getComponent(index, EntityStatMap.getComponentType());
        EntityStatValue health = statMap != null ? statMap.get(DefaultEntityStatTypes.getHealth()) : null;
        if (health == null || health.getMax() <= 0.0F) {
            return;
        }

        float threshold = health.getMax() / 3.0F;
        float before = health.get();
        float after = before - damage.getAmount();
        if (before < threshold || after >= threshold) {
            return;
        }

        PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastBurstMs.get(playerRef.getUuid());
        if (last != null && now - last < RECHARGE_MS) {
            return;
        }

        Interaction asset = Interaction.getAssetMap().getAsset(BURST_INTERACTION_ID);
        if (!(asset instanceof SporeBurstInteraction burst)) {
            if (!reportedMissing) {
                reportedMissing = true;
                LOGGER.atWarning().log("Spore burst skipped: interaction %s is missing or not an ArmorySporeBurst.", BURST_INTERACTION_ID);
            }
            return;
        }

        lastBurstMs.put(playerRef.getUuid(), now);
        burst.burst(chunk.getReferenceTo(index), commandBuffer);
    }
}
