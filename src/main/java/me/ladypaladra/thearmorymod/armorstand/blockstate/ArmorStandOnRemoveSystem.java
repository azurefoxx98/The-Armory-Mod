package me.ladypaladra.thearmorymod.armorstand.blockstate;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import me.ladypaladra.thearmorymod.armorstand.ArmorStandModule;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ArmorStandOnRemoveSystem — handles cleanup when an Armor Stand block is removed.
 *
 * When the block is broken, this system despawns the mannequin NPC and cleans
 * the transient runtime state from ArmorStandTickSystem.
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
    public void onEntityAdded(@Nonnull Ref<ChunkStore> ref,
                              @Nonnull AddReason reason,
                              @Nonnull Store<ChunkStore> store,
                              @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        // Nothing to do on add.
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<ChunkStore> ref,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<ChunkStore> store,
                               @Nonnull CommandBuffer<ChunkStore> commandBuffer) {

        if (reason == RemoveReason.UNLOAD) {
            return;
        }

        ArmorStandComponent armorComp = commandBuffer.getComponent(ref, armorStandComponentType);
        if (armorComp == null) return;

        BlockModule.BlockStateInfo blockInfo = commandBuffer.getComponent(ref, blockStateInfoComponentType);
        if (blockInfo == null) return;

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

                long posKey = com.hypixel.hytale.math.block.BlockUtil.pack(
                        worldX,
                        worldY,
                        worldZ
                );

                World world = commandBuffer.getExternalData().getWorld();

                ArmorStandTickSystem.cleanupBlock(posKey, world);

                LOGGER.info(
                        "ArmorStand block removed at "
                                + worldX + ","
                                + worldY + ","
                                + worldZ
                                + " — mannequin cleaned up"
                );
            }
        }

        UUID mannequinUuid = armorComp.getMannequinUuid();

        if (mannequinUuid != null) {
            try {
                World world = commandBuffer.getExternalData().getWorld();

                world.execute(() -> {
                    try {
                        Entity entity = world.getEntity(mannequinUuid);

                        if (entity != null) {
                            if (entity instanceof NPCEntity npc) {
                                ArmorStandModule.untrackMannequin(npc);
                            }

                            entity.remove();
                        }
                    } catch (Exception e) {
                        LOGGER.warning(
                                "Failed to remove persisted mannequin on block break: "
                                        + e.getMessage()
                        );
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