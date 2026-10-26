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
import me.ladypaladra.thearmorymod.stats.ArmoryStatIds;
import me.ladypaladra.thearmorymod.stats.GroundSpeedState;
import me.ladypaladra.thearmorymod.stats.LazyStatIndex;
import me.ladypaladra.thearmorymod.stats.StatMultiplier;

import javax.annotation.Nonnull;

/**
 * Makes a player move faster or slower on the ground while they wear armour carrying the move
 * speed stat. Walking, running, crouching and sprinting all change by the same amount, so a
 * slowing piece is felt at every pace and not only while sprinting.
 */
public final class GroundMoveSpeedModifierSystem extends EntityTickingSystem<EntityStore> {

    private final LazyStatIndex moveSpeedIndex = new LazyStatIndex(ArmoryStatIds.MOVE_SPEED);

    private final ComponentType<EntityStore, GroundSpeedState> stateType;

    private final Query<EntityStore> query = Archetype.of(
            PlayerRef.getComponentType(),
            EntityStatMap.getComponentType(),
            MovementManager.getComponentType()
    );

    public GroundMoveSpeedModifierSystem(@Nonnull ComponentType<EntityStore, GroundSpeedState> stateType) {
        this.stateType = stateType;
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

        float multiplier = StatMultiplier.forStat(statMap, moveSpeedIndex.get());
        GroundSpeedState state = chunk.getComponent(index, stateType);
        boolean created = state == null;
        if (created) {
            if (Math.abs(multiplier - 1.0F) <= StatMultiplier.EPSILON) {
                return;
            }
            state = new GroundSpeedState();
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
