package me.ladypaladra.thearmorymod.armorstand.blockstate;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Removes old persisted mannequin NPCs on entity load.
 *
 * Old versions allowed mannequin NPCs to remain persisted in the world.
 * This system deletes those loaded legacy mannequins so the block can respawn
 * the new visual-only mannequin cleanly.
 */
public final class ArmorStandLegacyMannequinSystem extends RefSystem<EntityStore> {

    private final Query<EntityStore> query = Query.any();

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> ref,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (reason != AddReason.LOAD) return;

        ArmorStandTickSystem.removeLoadedMannequinNpc(ref, store, commandBuffer);
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> ref,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // Nothing to do.
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return query;
    }
}