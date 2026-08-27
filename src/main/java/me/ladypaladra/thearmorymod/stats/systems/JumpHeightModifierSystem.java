package me.ladypaladra.thearmorymod.stats.systems;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.stats.ArmoryStatIds;
import me.ladypaladra.thearmorymod.stats.LazyStatIndex;
import me.ladypaladra.thearmorymod.stats.StatMultiplier;

import javax.annotation.Nonnull;

public final class JumpHeightModifierSystem extends EntityTickingSystem<EntityStore> {

    private final LazyStatIndex jumpHeightIndex = new LazyStatIndex(ArmoryStatIds.JUMP_HEIGHT);

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

        float multiplier = getJumpHeightMultiplier(statMap);
        float targetJumpForce = movementManager.getDefaultSettings().jumpForce * multiplier;
        float targetSwimJumpForce = movementManager.getDefaultSettings().swimJumpForce * multiplier;
        float liveJumpForce = movementManager.getSettings().jumpForce;
        float liveSwimJumpForce = movementManager.getSettings().swimJumpForce;

        // Live settings are the state, so a replacement manager after reconnect corrects itself.
        if (Math.abs(liveJumpForce - targetJumpForce) <= StatMultiplier.EPSILON
                && Math.abs(liveSwimJumpForce - targetSwimJumpForce) <= StatMultiplier.EPSILON) {
            return;
        }

        movementManager.getSettings().jumpForce = targetJumpForce;
        movementManager.getSettings().swimJumpForce = targetSwimJumpForce;
        movementManager.update(playerRef.getPacketHandler());
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        // This writes live movement settings and sends a packet, so it must run on the world thread.
        return false;
    }

    private float getJumpHeightMultiplier(@Nonnull EntityStatMap statMap) {
        return StatMultiplier.forStat(statMap, jumpHeightIndex.get());
    }
}
