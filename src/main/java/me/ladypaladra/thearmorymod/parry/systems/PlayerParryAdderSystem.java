package me.ladypaladra.thearmorymod.parry.systems;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.parry.components.ParryComponent;

import javax.annotation.Nonnull;

/**
 * Attaches the parry component to every player entity.
 */
public class PlayerParryAdderSystem extends RefSystem<EntityStore> {

    private final ComponentType<EntityStore, ParryComponent> parryComponentType;

    public PlayerParryAdderSystem(ComponentType<EntityStore, ParryComponent> parryComponentType) {
        this.parryComponentType = parryComponentType;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> ref,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (commandBuffer.getComponent(ref, parryComponentType) == null) {
            commandBuffer.addComponent(ref, parryComponentType, new ParryComponent());
        }
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> ref,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
    }
}
