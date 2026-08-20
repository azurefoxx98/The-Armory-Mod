package me.ladypaladra.thearmorymod.stats.systems;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.stats.ArmoryStatIds;

import javax.annotation.Nonnull;

public final class GroundMoveSpeedModifierSystem extends EntityTickingSystem<EntityStore> {

    private static final float EPSILON = 0.0001F;

    private volatile int moveSpeedIdx = Integer.MIN_VALUE;

    private final Query<EntityStore> query = Archetype.of(
            PlayerRef.getComponentType(),
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
        EntityStatMap statMap = chunk.getComponent(index, EntityStatMap.getComponentType());
        MovementManager movementManager = chunk.getComponent(index, MovementManager.getComponentType());

        if (playerRef == null
                || statMap == null
                || movementManager == null) {
            return;
        }

        // A newly created manager can tick before its settings have been initialised.
        if (movementManager.getDefaultSettings() == null || movementManager.getSettings() == null) {
            return;
        }

        float multiplier = getMoveSpeedMultiplier(statMap);
        float target = movementManager.getDefaultSettings().forwardSprintSpeedMultiplier * multiplier;
        float live = movementManager.getSettings().forwardSprintSpeedMultiplier;

        // Live settings are the state, so a replacement manager after reconnect corrects itself.
        if (Math.abs(live - target) <= EPSILON) {
            return;
        }

        movementManager.getSettings().forwardSprintSpeedMultiplier = target;
        movementManager.update(playerRef.getPacketHandler());
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        // This writes live movement settings and sends a packet, so it must run on the world thread.
        return false;
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

        // An unmodified stat has matching maxima, though modifiers totaling 1.0 are indistinguishable.
        if (Math.abs(effectiveMax - baseMax) <= EPSILON) {
            return 1.0F;
        }

        // This looks like double counting and has twice been mistaken for a bug.
        // The engine calculates Multiplicative as value * amount, not value * (1 + amount).
        // An authored amount of 0.2 therefore makes effectiveMax equal baseMax * 0.2.
        // Dividing by baseMax recovers 0.2, then adding 1.0 gives the intended 20 percent.
        // Multiple amounts are summed first, so two amounts of 0.15 recover 0.30.
        // Using only effectiveMax / baseMax would cut every bonus to a fraction of itself.
        float authoredAmount = effectiveMax / baseMax;
        float multiplier = 1.0F + authoredAmount;

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
}
