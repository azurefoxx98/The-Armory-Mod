package me.ladypaladra.thearmorymod.armorstand.blockstate;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Handles cleanup when an ArmorStand block entity is removed (block broken).
 * Despawns the mannequin NPC and cleans up runtime state.
 */
@SuppressWarnings({"removal", "deprecation"})
public class ArmorStandOnRemoveSystem extends RefSystem<ChunkStore> {

    private static final Logger LOGGER = Logger.getLogger("TheArmory-ArmorStand");

    private final ComponentType<ChunkStore, ArmorStandComponent> armorStandComponentType;
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockStateInfoComponentType =
            BlockModule.BlockStateInfo.getComponentType();
    private final Query<ChunkStore> query;

    public ArmorStandOnRemoveSystem(ComponentType<ChunkStore, ArmorStandComponent> componentType) {
        this.armorStandComponentType = componentType;
        this.query = Query.and(armorStandComponentType, blockStateInfoComponentType);
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<ChunkStore> ref, @Nonnull AddReason reason,
                              @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        // Apply the slot filters as soon as the block entity exists — on placement AND
        // on chunk load — so there is never a window where the container is unfiltered
        // and the built-in sort / quick-stack / transfer buttons could duplicate items.
        // (Container filters are transient and are NOT persisted, so they must be
        //  re-applied every time the block is loaded, not only in the tick loop.)
        try {
            ItemContainerBlock containerBlock =
                    commandBuffer.getComponent(ref, ItemContainerBlock.getComponentType());
            if (containerBlock == null) return;

            SimpleItemContainer container = containerBlock.getItemContainer();
            if (container == null) return;

            ArmorStandTickSystem.applySlotFilters(container);
        } catch (Exception e) {
            LOGGER.warning("Could not apply armor slot filters on add: " + e.getMessage());
        }
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<ChunkStore> ref, @Nonnull RemoveReason reason,
                               @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        // Don't clean up on chunk unload — only on actual block break
        if (reason == RemoveReason.UNLOAD) {
            return;
        }

        ArmorStandComponent armorComp = commandBuffer.getComponent(ref, armorStandComponentType);
        if (armorComp == null) return;

        BlockModule.BlockStateInfo blockInfo = commandBuffer.getComponent(ref, blockStateInfoComponentType);
        if (blockInfo == null) return;

        // Resolve world position to clean up runtime map
        Ref<ChunkStore> chunkRef = blockInfo.getChunkRef();
        if (chunkRef != null && chunkRef.isValid()) {
            int packedIndex = blockInfo.getIndex();
            int localX = ChunkUtil.xFromBlockInColumn(packedIndex);
            int worldY = ChunkUtil.yFromBlockInColumn(packedIndex);
            int localZ = ChunkUtil.zFromBlockInColumn(packedIndex);

            BlockChunk blockChunk = commandBuffer.getComponent(chunkRef, BlockChunk.getComponentType());
            if (blockChunk != null) {
                int worldX = blockChunk.getX() * ChunkUtil.SIZE + localX;
                int worldZ = blockChunk.getZ() * ChunkUtil.SIZE + localZ;
                long posKey = com.hypixel.hytale.math.block.BlockUtil.pack(worldX, worldY, worldZ);

                // Clean up via ArmorStandTickSystem's static cleanup (removes runtime + despawns NPC)
                World world = commandBuffer.getExternalData().getWorld();
                ArmorStandTickSystem.cleanupBlock(posKey, world);
                LOGGER.info("ArmorStand block removed at " + worldX + "," + worldY + "," + worldZ + " — mannequin cleaned up");
            }
        }

        // Also try to remove by persisted UUID as fallback
        UUID mannequinUuid = armorComp.getMannequinUuid();
        if (mannequinUuid != null) {
            try {
                World world = commandBuffer.getExternalData().getWorld();
                world.execute(() -> {
                    try {
                        ArmorStandTickSystem.removeMannequinByUuid(world, mannequinUuid);
                    } catch (Exception e) {
                        LOGGER.warning("Failed to remove persisted mannequin on block break: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                LOGGER.warning("Error during mannequin cleanup: " + e.getMessage());
            }
        }
    }

    @Override
    @Nullable
    public Query<ChunkStore> getQuery() {
        return query;
    }
}
