package me.ladypaladra.thearmorymod.showcase;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;

/**
 * Keeps a figure on every showcase stand: builds it when the stand loads or when its figure is gone,
 * takes it away with the stand, and keeps it from being hurt.
 */
public final class ShowcaseFigureSystems {

    private ShowcaseFigureSystems() {
    }

    public static class Tick extends EntityTickingSystem<ChunkStore> {

        @Nonnull
        private final Archetype<ChunkStore> archetype = Archetype.of(
                ShowcaseFigureStand.getComponentType(),
                BlockModule.BlockStateInfo.getComponentType()
        );

        @Override
        public Query<ChunkStore> getQuery() {
            return archetype;
        }

        // A figure that could not be built is tried again after this long, not on every tick, so a missing
        // role or item costs one warning per stand every few seconds instead of flooding the log.
        private static final float RETRY_DELAY_SECONDS = 5.0F;

        @Override
        public void tick(
                float dt,
                int index,
                @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
                @Nonnull Store<ChunkStore> store,
                @Nonnull CommandBuffer<ChunkStore> commandBuffer
        ) {
            ShowcaseFigureStand stand = archetypeChunk.getComponent(index, ShowcaseFigureStand.getComponentType());
            BlockModule.BlockStateInfo blockStateInfo =
                    archetypeChunk.getComponent(index, BlockModule.BlockStateInfo.getComponentType());

            if (stand == null || blockStateInfo == null || stand.spawnPending || stand.isEmpty()
                    || ShowcaseFigure.isAlive(stand.figure)) {
                return;
            }

            if (stand.retryDelaySeconds > 0.0F) {
                stand.retryDelaySeconds -= dt;
                return;
            }

            Ref<ChunkStore> sectionRef = blockStateInfo.getSectionRef();
            Vector3i position = new Vector3i();

            if (sectionRef == null || !sectionRef.isValid() || !blockStateInfo.fillWorldPos(store, position)) {
                return;
            }

            float yaw = 0.0F;
            BlockSection blockSection = store.getComponent(sectionRef, BlockSection.getComponentType());

            if (blockSection != null) {
                RotationTuple rotation = blockSection.getRotation(blockStateInfo.getIndex());

                if (rotation != null && rotation.yaw() != null) {
                    yaw = (float) rotation.yaw().getRadians();
                }
            }

            World world = store.getExternalData().getWorld();

            if (world == null) {
                return;
            }

            // Entities cannot be added while the chunk store is ticking, so the figure is built on the
            // world queue. The generation number lets a stand removed meanwhile cancel it.
            stand.figure = null;
            stand.spawnPending = true;
            int generation = ++stand.spawnGeneration;
            int x = position.x;
            int y = position.y;
            int z = position.z;
            float blockYaw = yaw;

            world.execute(() -> {
                stand.spawnPending = false;

                if (stand.removed || generation != stand.spawnGeneration) {
                    return;
                }

                stand.figure = ShowcaseFigure.spawn(world, stand, x, y, z, blockYaw);

                if (stand.figure == null) {
                    stand.retryDelaySeconds = RETRY_DELAY_SECONDS;
                }
            });
        }
    }

    public static class Remove extends RefSystem<ChunkStore> {

        @Override
        public Query<ChunkStore> getQuery() {
            return ShowcaseFigureStand.getComponentType();
        }

        @Override
        public void onEntityAdded(
                @Nonnull Ref<ChunkStore> ref,
                @Nonnull AddReason reason,
                @Nonnull Store<ChunkStore> store,
                @Nonnull CommandBuffer<ChunkStore> commandBuffer
        ) {
        }

        @Override
        public void onEntityRemove(
                @Nonnull Ref<ChunkStore> ref,
                @Nonnull RemoveReason reason,
                @Nonnull Store<ChunkStore> store,
                @Nonnull CommandBuffer<ChunkStore> commandBuffer
        ) {
            ShowcaseFigureStand stand = commandBuffer.getComponent(ref, ShowcaseFigureStand.getComponentType());

            if (stand == null) {
                return;
            }

            stand.removed = true;
            stand.spawnGeneration++;

            // An unloading chunk takes its figure with it, and the figure is never saved.
            if (reason == RemoveReason.UNLOAD || !ShowcaseFigure.isAlive(stand.figure)) {
                return;
            }

            Ref<EntityStore> figure = stand.figure;
            stand.figure = null;
            World world = commandBuffer.getExternalData().getWorld();

            if (world != null) {
                world.execute(() -> ShowcaseFigure.remove(figure));
            }
        }
    }

    /**
     * Cancels every hit on a showcase figure. A figure is scenery, so nothing may take it off
     * display or make it drop what it wears.
     */
    public static class Shield extends DamageEventSystem {

        @Override
        public Query<EntityStore> getQuery() {
            return ShowcaseFigure.Shield.getComponentType();
        }

        @Override
        public SystemGroup<EntityStore> getGroup() {
            return DamageModule.get().getFilterDamageGroup();
        }

        @Override
        public void handle(
                int index,
                @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull Damage damage
        ) {
            damage.setCancelled(true);
        }
    }
}
