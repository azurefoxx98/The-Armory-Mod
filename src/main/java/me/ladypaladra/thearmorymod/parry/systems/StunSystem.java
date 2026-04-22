package me.ladypaladra.thearmorymod.parry.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.parry.components.StunComponent;
import me.ladypaladra.thearmorymod.parry.util.StunUtil;

import javax.annotation.Nonnull;

public class StunSystem extends EntityTickingSystem<EntityStore> {

    private final ComponentType<EntityStore, StunComponent> stunComponentType;

    public StunSystem(ComponentType<EntityStore, StunComponent> stunComponentType) {
        this.stunComponentType = stunComponentType;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return stunComponentType;
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        StunComponent stunComponent = chunk.getComponent(index, stunComponentType);
        if (stunComponent == null) {
            return;
        }

        float remaining = stunComponent.getTimeRemaining() - dt;
        Ref<EntityStore> entityRef = chunk.getReferenceTo(index);

        if (remaining <= 0.0F) {
            StunUtil.removeStunEffect(entityRef, store, commandBuffer);
            commandBuffer.removeComponent(entityRef, stunComponentType);
            return;
        }

        stunComponent.setTimeRemaining(remaining);
        StunUtil.enforceStun(entityRef, store, commandBuffer);
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }
}