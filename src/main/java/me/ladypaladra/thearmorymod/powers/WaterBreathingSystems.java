package me.ladypaladra.thearmorymod.powers;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.event.events.ecs.BreathingCheckEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.modules.entity.component.BreathingComponent;
import com.hypixel.hytale.server.core.modules.entity.component.CachedStatsComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets a player wearing a water breathing piece breathe in water.
 *
 * <p>The engine asks every handler of {@link BreathingCheckEvent} whether an entity can breathe
 * whenever the block or fluid at its breathing height changes. Answering yes in water is the
 * whole power. Because that question is only asked on a change of surroundings, putting the
 * helmet on or taking it off while already underwater would otherwise change nothing until the
 * player moved, so a second system asks the question again whenever the answer would flip.</p>
 */
public final class WaterBreathingSystems {

    private WaterBreathingSystems() {
    }

    static boolean isWater(int fluidId) {
        if (fluidId == Fluid.EMPTY_ID) {
            return false;
        }
        Fluid fluid = Fluid.getAssetMap().getAsset(fluidId);
        return fluid != null && fluid.getId() != null && fluid.getId().startsWith("Water");
    }

    /**
     * Answers the engine's breathing question.
     */
    public static final class Check extends EntityEventSystem<EntityStore, BreathingCheckEvent> {

        private final Query<EntityStore> query = Archetype.of(
                PlayerRef.getComponentType(),
                InventoryComponent.Armor.getComponentType()
        );

        public Check() {
            super(BreathingCheckEvent.class);
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
                @Nonnull BreathingCheckEvent event
        ) {
            if (event.canBreathe() || !isWater(event.getFluidId())) {
                return;
            }

            if (WornTags.wears(chunk.getComponent(index, InventoryComponent.Armor.getComponentType()), WornTags.WATER_BREATHING)) {
                event.setCanBreathe(true);
            }
        }
    }

    /**
     * Asks the breathing question again when a player puts the piece on or takes it off.
     */
    public static final class Recheck extends EntityTickingSystem<EntityStore> {

        private final Set<UUID> wearing = ConcurrentHashMap.newKeySet();

        private final Query<EntityStore> query = Archetype.of(
                PlayerRef.getComponentType(),
                InventoryComponent.Armor.getComponentType(),
                BreathingComponent.getComponentType()
        );

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public void tick(
                float dt,
                int index,
                @Nonnull ArchetypeChunk<EntityStore> chunk,
                @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer
        ) {
            PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
            BreathingComponent breathing = chunk.getComponent(index, BreathingComponent.getComponentType());
            if (playerRef == null || breathing == null) {
                return;
            }

            boolean wearsNow = WornTags.wears(chunk.getComponent(index, InventoryComponent.Armor.getComponentType()), WornTags.WATER_BREATHING);
            boolean woreBefore = wearsNow ? !wearing.add(playerRef.getUuid()) : wearing.remove(playerRef.getUuid());
            if (wearsNow == woreBefore) {
                return;
            }

            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            CachedStatsComponent cachedStats = chunk.getComponent(index, CachedStatsComponent.getComponentType());
            boolean invulnerable = chunk.getArchetype().contains(Invulnerable.getComponentType());
            EntityUtils.processEntityBreathing(ref, breathing, cachedStats, invulnerable, true, commandBuffer);
        }

        @Override
        public boolean isParallel(int archetypeChunkSize, int taskCount) {
            // The recheck invokes an event on the entity, so it runs on the world thread.
            return false;
        }
    }
}
