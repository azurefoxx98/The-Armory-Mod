package me.ladypaladra.thearmorymod.largebench.interaction;

import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.TheArmoryMod;
import me.ladypaladra.thearmorymod.largebench.window.LargeStructuralCraftingWindow;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

public final class OpenLargeStructuralCraftingBenchInteraction extends SimpleBlockInteraction {

    @Nonnull
    public static final BuilderCodec<OpenLargeStructuralCraftingBenchInteraction> CODEC;

    public OpenLargeStructuralCraftingBenchInteraction() {
    }

    @Override
    protected void interactWithBlock(
            @Nonnull World world,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nullable ItemStack itemInHand,
            @Nonnull Vector3i targetBlock,
            @Nonnull CooldownHandler cooldownHandler
    ) {
        Ref<EntityStore> playerRef = context.getEntity();
        Store<EntityStore> entityStore = playerRef.getStore();

        Player player = commandBuffer.getComponent(playerRef, Player.getComponentType());

        if (player == null) {
            TheArmoryMod.LOGGER.atWarning().log("Could not open large structural bench: player component not found.");
            return;
        }

        CraftingManager craftingManager = commandBuffer.getComponent(playerRef, CraftingManager.getComponentType());

        if (craftingManager == null) {
            TheArmoryMod.LOGGER.atWarning().log("Could not open large structural bench: crafting manager not found.");
            return;
        }

        if (craftingManager.hasBenchSet()) {
            TheArmoryMod.LOGGER.atWarning().log("Could not open large structural bench: another bench is already open.");
            return;
        }

        ChunkStore chunkStore = world.getChunkStore();

        Ref<ChunkStore> chunkRef = chunkStore.getChunkReference(
                ChunkUtil.indexChunkFromBlock(targetBlock.x, targetBlock.z)
        );

        if (chunkRef == null || !chunkRef.isValid()) {
            TheArmoryMod.LOGGER.atWarning().log("Could not open large structural bench: chunk not found.");
            return;
        }

        Store<ChunkStore> chunkComponentStore = chunkStore.getStore();

        BlockComponentChunk blockComponentChunk = chunkComponentStore.getComponent(
                chunkRef,
                BlockComponentChunk.getComponentType()
        );

        if (blockComponentChunk == null) {
            TheArmoryMod.LOGGER.atWarning().log("Could not open large structural bench: block component chunk not found.");
            return;
        }

        Ref<ChunkStore> blockEntityRef = blockComponentChunk.getEntityReference(
                ChunkUtil.indexBlockInColumn(targetBlock.x, targetBlock.y, targetBlock.z)
        );

        if (blockEntityRef == null || !blockEntityRef.isValid()) {
            TheArmoryMod.LOGGER.atWarning().log("Could not open large structural bench: block entity not found.");
            return;
        }

        BenchBlock benchBlock = chunkComponentStore.getComponent(
                blockEntityRef,
                BenchBlock.getComponentType()
        );

        if (benchBlock == null) {
            TheArmoryMod.LOGGER.atWarning().log("Could not open large structural bench: BenchBlock component not found.");
            return;
        }

        BlockType blockType = world.getBlockType(targetBlock.x, targetBlock.y, targetBlock.z);

        if (blockType == null || blockType == BlockType.EMPTY || blockType == BlockType.UNKNOWN) {
            TheArmoryMod.LOGGER.atWarning().log("Could not open large structural bench: invalid block type.");
            return;
        }

        if (blockType.getBench() == null || blockType.getBench().getType() != BenchType.StructuralCrafting) {
            TheArmoryMod.LOGGER.atWarning().log("Could not open large structural bench: block is not a structural crafting bench.");
            return;
        }

        WorldChunk worldChunk = world.getChunk(
                ChunkUtil.indexChunkFromBlock(targetBlock.x, targetBlock.z)
        );

        if (worldChunk == null) {
            TheArmoryMod.LOGGER.atWarning().log("Could not open large structural bench: world chunk not found.");
            return;
        }

        UUIDComponent uuidComponent = commandBuffer.getComponent(
                playerRef,
                UUIDComponent.getComponentType()
        );

        if (uuidComponent == null) {
            TheArmoryMod.LOGGER.atWarning().log("Could not open large structural bench: uuid component not found.");
            return;
        }

        BlockModule.BlockStateInfo blockStateInfo = chunkComponentStore.getComponent(
                blockEntityRef,
                BlockModule.BlockStateInfo.getComponentType()
        );

        int rotationIndex = worldChunk.getRotationIndex(
                targetBlock.x,
                targetBlock.y,
                targetBlock.z
        );

        UUID uuid = uuidComponent.getUuid();

        LargeStructuralCraftingWindow window = new LargeStructuralCraftingWindow(
                targetBlock.x,
                targetBlock.y,
                targetBlock.z,
                rotationIndex,
                blockType,
                benchBlock
        );

        if (benchBlock.getWindows().putIfAbsent(uuid, window) != null) {
            TheArmoryMod.LOGGER.atWarning().log("Large structural bench is already open.");
            return;
        }

        window.registerCloseEvent(event -> {
            benchBlock.getWindows().remove(uuid, window);

            if (blockStateInfo != null) {
                blockStateInfo.markNeedsSaving();
            }
        });

        boolean opened = player.getPageManager().setPageWithWindows(
                playerRef,
                entityStore,
                Page.Bench,
                true,
                window
        );

        if (!opened) {
            benchBlock.getWindows().remove(uuid, window);
            TheArmoryMod.LOGGER.atWarning().log("Could not open large structural bench page.");
        }
    }

    @Override
    protected void simulateInteractWithBlock(
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nullable ItemStack itemInHand,
            @Nonnull World world,
            @Nonnull Vector3i targetBlock
    ) {
    }

    static {
        CODEC = BuilderCodec
                .builder(
                        OpenLargeStructuralCraftingBenchInteraction.class,
                        OpenLargeStructuralCraftingBenchInteraction::new,
                        SimpleBlockInteraction.CODEC
                )
                .documentation("Opens a structural crafting bench with more than 64 option slots.")
                .build();
    }
}