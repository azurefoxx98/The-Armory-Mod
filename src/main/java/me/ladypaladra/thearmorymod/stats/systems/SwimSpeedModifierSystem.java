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
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.stats.ArmoryStatIds;
import me.ladypaladra.thearmorymod.stats.LazyStatIndex;
import me.ladypaladra.thearmorymod.stats.StatMultiplier;
import me.ladypaladra.thearmorymod.stats.SwimBoostState;

import javax.annotation.Nonnull;

/**
 * Makes a player swim faster while they wear armour carrying the swim speed stat.
 *
 * <p>Swim speed lives in each fluid's movement settings rather than in a per-player field, so
 * the boost raises the player's base speed while they are swimming and puts it back when they
 * stop. It only ever undoes its own change. If something else replaced the live settings while
 * the boost was on, the new value is left as it is.</p>
 */
public final class SwimSpeedModifierSystem extends EntityTickingSystem<EntityStore> {

    /**
     * The fastest the boost may make swimming. Pieces stack, so this keeps a full set at the
     * same top speed the set's designer wrote for the leggings alone.
     */
    public static final float MAX_MULTIPLIER = 3.0F;

    private final LazyStatIndex swimSpeedIndex = new LazyStatIndex(ArmoryStatIds.SWIM_SPEED);

    private final ComponentType<EntityStore, SwimBoostState> stateType;

    private final Query<EntityStore> query = Archetype.of(
            PlayerRef.getComponentType(),
            EntityStatMap.getComponentType(),
            MovementManager.getComponentType(),
            MovementStatesComponent.getComponentType()
    );

    public SwimSpeedModifierSystem(@Nonnull ComponentType<EntityStore, SwimBoostState> stateType) {
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
        MovementStatesComponent movementStates = chunk.getComponent(index, MovementStatesComponent.getComponentType());

        if (playerRef == null || statMap == null || movementManager == null || movementStates == null) {
            return;
        }

        // A newly created manager can tick before its settings have been initialised.
        MovementSettings live = movementManager.getSettings();
        if (live == null || movementManager.getDefaultSettings() == null) {
            return;
        }

        SwimBoostState state = chunk.getComponent(index, stateType);
        float multiplier = Math.min(StatMultiplier.forStat(statMap, swimSpeedIndex.get()), MAX_MULTIPLIER);
        boolean wantBoost = movementStates.getMovementStates().swimming && multiplier > 1.0F + StatMultiplier.EPSILON;

        if (wantBoost) {
            float base = live.baseSpeed;
            if (state != null && state.isActive() && sameSpeed(live.baseSpeed, state.getBoostedSpeed())) {
                // Our own boost is still in place, so the speed it started from is still the base.
                base = state.getBaseSpeed();
            }

            float target = base * multiplier;
            if (state == null || !state.isActive() || !sameSpeed(live.baseSpeed, target)) {
                live.baseSpeed = target;
                movementManager.update(playerRef.getPacketHandler());
                remember(index, chunk, commandBuffer, state, base, target);
            }
            return;
        }

        if (state != null && state.isActive()) {
            // Restore only if the live value is still the one we wrote.
            if (sameSpeed(live.baseSpeed, state.getBoostedSpeed())) {
                live.baseSpeed = state.getBaseSpeed();
                movementManager.update(playerRef.getPacketHandler());
            }
            state.stop();
        }
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        // This writes live movement settings and sends a packet, so it must run on the world thread.
        return false;
    }

    private void remember(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> chunk,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            SwimBoostState state,
            float base,
            float target
    ) {
        if (state != null) {
            state.start(base, target);
            return;
        }
        // Filled before it is handed over, so the stored component is complete whether the
        // buffer keeps this instance or a copy of it.
        SwimBoostState created = new SwimBoostState();
        created.start(base, target);
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        commandBuffer.putComponent(ref, stateType, created);
    }

    private static boolean sameSpeed(float a, float b) {
        return Math.abs(a - b) <= StatMultiplier.EPSILON;
    }
}
