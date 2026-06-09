package me.ladypaladra.thearmorymod.stats.systems;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.stats.ArmoryStatIds;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class GroundMoveSpeedModifierSystem extends EntityTickingSystem<EntityStore> {

    private static final float EPSILON = 0.0001F;

    private volatile int moveSpeedIdx = Integer.MIN_VALUE;

    private final Map<UUID, SpeedState> states = new HashMap<>();

    private final Query<EntityStore> query = Archetype.of(
            PlayerRef.getComponentType(),
            UUIDComponent.getComponentType(),
            EntityStatMap.getComponentType(),
            MovementManager.getComponentType()
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
        UUIDComponent uuidComponent = chunk.getComponent(index, UUIDComponent.getComponentType());
        EntityStatMap statMap = chunk.getComponent(index, EntityStatMap.getComponentType());
        MovementManager movementManager = chunk.getComponent(index, MovementManager.getComponentType());

        if (playerRef == null
                || uuidComponent == null
                || statMap == null
                || movementManager == null) {
            return;
        }

        UUID uuid = uuidComponent.getUuid();
        SpeedState state = states.computeIfAbsent(uuid, ignored -> new SpeedState());

        float multiplier = getMoveSpeedMultiplier(statMap);

        if (multiplier <= 1.0F + EPSILON) {
            resetSprintSpeedIfNeeded(playerRef, movementManager, state);
            return;
        }

        applySprintSpeedIfNeeded(playerRef, movementManager, state, uuid, multiplier);
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }

    private void applySprintSpeedIfNeeded(
            @Nonnull PlayerRef playerRef,
            @Nonnull MovementManager movementManager,
            @Nonnull SpeedState state,
            @Nonnull UUID uuid,
            float multiplier
    ) {
        if (state.applied && Math.abs(state.lastMultiplier - multiplier) <= EPSILON) {
            return;
        }

        float defaultSprintMultiplier =
                movementManager.getDefaultSettings().forwardSprintSpeedMultiplier;

        float newSprintMultiplier = defaultSprintMultiplier * multiplier;

        movementManager.getSettings().forwardSprintSpeedMultiplier = newSprintMultiplier;

        movementManager.update(playerRef.getPacketHandler());

        state.applied = true;
        state.lastMultiplier = multiplier;
    }

    private void resetSprintSpeedIfNeeded(
            @Nonnull PlayerRef playerRef,
            @Nonnull MovementManager movementManager,
            @Nonnull SpeedState state
    ) {
        if (!state.applied) {
            return;
        }

        movementManager.getSettings().forwardSprintSpeedMultiplier = movementManager.getDefaultSettings().forwardSprintSpeedMultiplier;

        movementManager.update(playerRef.getPacketHandler());

        state.applied = false;
        state.lastMultiplier = 1.0F;
    }

    private float getMoveSpeedMultiplier(@Nonnull EntityStatMap statMap) {
        int statIndex = resolveMoveSpeedIdx();

        if (statIndex == Integer.MIN_VALUE) {
            return 1.0F;
        }

        EntityStatValue value = statMap.get(statIndex);

        if (value == null) {
            statMap.update();
            value = statMap.get(statIndex);
        }

        if (value == null) {
            return 1.0F;
        }

        EntityStatType statType = EntityStatType.getAssetMap().getAsset(statIndex);

        if (statType == null) {
            return 1.0F;
        }

        float baseMax = statType.getMax();
        float effectiveMax = value.getMax();

        if (baseMax <= 0.0F) {
            return 1.0F;
        }

        if (Math.abs(effectiveMax - baseMax) <= EPSILON) {
            return 1.0F;
        }

        float bonusFraction = effectiveMax / baseMax;
        float multiplier = 1.0F + bonusFraction;

        return Math.max(1.0F, multiplier);
    }

    private int resolveMoveSpeedIdx() {
        int idx = moveSpeedIdx;

        if (idx != Integer.MIN_VALUE) {
            return idx;
        }

        int found = EntityStatType.getAssetMap().getIndex(ArmoryStatIds.MOVE_SPEED);

        if (found != Integer.MIN_VALUE) {
            moveSpeedIdx = found;
            return found;
        }

        return Integer.MIN_VALUE;
    }

    private static final class SpeedState {
        private boolean applied = false;
        private float lastMultiplier = 1.0F;
    }
}