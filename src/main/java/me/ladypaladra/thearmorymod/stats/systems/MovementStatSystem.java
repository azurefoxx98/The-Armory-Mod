package me.ladypaladra.thearmorymod.stats.systems;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.stats.LazyStatIndex;
import me.ladypaladra.thearmorymod.stats.ScaledMovementState;
import me.ladypaladra.thearmorymod.stats.StatMultiplier;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

/**
 * Applies an armour stat's multiplier to some fields of a player's live movement settings, through
 * a {@link ScaledMovementState} that remembers the change so it can be undone exactly.
 */
abstract class MovementStatSystem<S extends ScaledMovementState> extends EntityTickingSystem<EntityStore> {

    private final LazyStatIndex statIndex;

    private final ComponentType<EntityStore, S> stateType;

    private final Supplier<S> newState;

    private final Query<EntityStore> query = Archetype.of(
            PlayerRef.getComponentType(),
            EntityStatMap.getComponentType(),
            MovementManager.getComponentType()
    );

    MovementStatSystem(
            @Nonnull String statId,
            @Nonnull ComponentType<EntityStore, S> stateType,
            @Nonnull Supplier<S> newState
    ) {
        this.statIndex = new LazyStatIndex(statId);
        this.stateType = stateType;
        this.newState = newState;
    }

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
        EntityStatMap statMap = chunk.getComponent(index, EntityStatMap.getComponentType());
        MovementManager movementManager = chunk.getComponent(index, MovementManager.getComponentType());

        if (playerRef == null
                || statMap == null
                || movementManager == null) {
            return;
        }

        // A newly created manager can tick before its settings have been initialised.
        MovementSettings live = movementManager.getSettings();
        if (live == null || movementManager.getDefaultSettings() == null) {
            return;
        }

        float multiplier = StatMultiplier.forStat(statMap, statIndex.get());
        S state = chunk.getComponent(index, stateType);
        boolean created = state == null;
        if (created) {
            if (Math.abs(multiplier - 1.0F) <= StatMultiplier.EPSILON) {
                return;
            }
            state = newState.get();
        }

        if (state.reconcile(live, multiplier)) {
            movementManager.update(playerRef.getPacketHandler());
        }

        if (created) {
            // Filled before it is handed over, so the stored component is complete whether the
            // buffer keeps this instance or a copy of it.
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            commandBuffer.putComponent(ref, stateType, state);
        }
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        // This writes live movement settings and sends a packet, so it must run on the world thread.
        return false;
    }
}
