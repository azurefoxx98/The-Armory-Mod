package me.ladypaladra.thearmorymod.tailoring.interaction;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.tailoring.component.ArmorTailoringBenchBlock;
import me.ladypaladra.thearmorymod.tailoring.window.ArmorTailoringBenchWindow;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

public final class OpenArmorTailoringBenchInteraction extends SimpleBlockInteraction {

    @Nonnull
    public static final BuilderCodec<OpenArmorTailoringBenchInteraction> CODEC;

    public OpenArmorTailoringBenchInteraction() {
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
        Ref<EntityStore> ref = context.getEntity();
        Store<EntityStore> store = ref.getStore();

        Player player = commandBuffer.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }

        UUIDComponent uuidComponent = commandBuffer.getComponent(ref, UUIDComponent.getComponentType());
        if (uuidComponent == null) {
            player.sendMessage(Message.raw("Could not open tailoring bench: UUID component not found."));
            return;
        }

        ChunkStore chunkStore = world.getChunkStore();
        Ref<ChunkStore> chunkRef = chunkStore.getChunkReference(
                ChunkUtil.indexChunkFromBlock(targetBlock.x, targetBlock.z)
        );

        if (chunkRef == null || !chunkRef.isValid()) {
            player.sendMessage(Message.raw("Could not open tailoring bench: chunk not found."));
            return;
        }

        Store<ChunkStore> chunkComponentStore = chunkStore.getStore();

        BlockComponentChunk blockComponentChunk = chunkComponentStore.getComponent(
                chunkRef,
                BlockComponentChunk.getComponentType()
        );

        if (blockComponentChunk == null) {
            player.sendMessage(Message.raw("Could not open tailoring bench: block component chunk not found."));
            return;
        }

        Ref<ChunkStore> blockEntityRef = blockComponentChunk.getEntityReference(
                ChunkUtil.indexBlockInColumn(targetBlock.x, targetBlock.y, targetBlock.z)
        );

        if (blockEntityRef == null || !blockEntityRef.isValid()) {
            player.sendMessage(Message.raw("Could not open tailoring bench: block entity not found."));
            return;
        }

        ArmorTailoringBenchBlock tailoringState = chunkComponentStore.getComponent(
                blockEntityRef,
                ArmorTailoringBenchBlock.getComponentType()
        );

        if (tailoringState == null) {
            player.sendMessage(Message.raw("Could not open tailoring bench: ArmorTailoringBenchBlock component not found."));
            return;
        }

        BlockModule.BlockStateInfo blockStateInfo = chunkComponentStore.getComponent(
                blockEntityRef,
                BlockModule.BlockStateInfo.getComponentType()
        );

        BlockType blockType = world.getBlockType(targetBlock.x, targetBlock.y, targetBlock.z);
        if (blockType == null || blockType == BlockType.EMPTY || blockType == BlockType.UNKNOWN) {
            player.sendMessage(Message.raw("Could not open tailoring bench: invalid block type."));
            return;
        }

        WorldChunk worldChunk = world.getChunk(
                ChunkUtil.indexChunkFromBlock(targetBlock.x, targetBlock.z)
        );

        if (worldChunk == null) {
            player.sendMessage(Message.raw("Could not open tailoring bench: world chunk not found."));
            return;
        }

        int rotationIndex = worldChunk.getRotationIndex(
                targetBlock.x,
                targetBlock.y,
                targetBlock.z
        );

        UUID uuid = uuidComponent.getUuid();

        if (tailoringState.hasWindow(uuid)) {
            return;
        }

        ArmorTailoringBenchWindow window = new ArmorTailoringBenchWindow(
                tailoringState,
                blockStateInfo,
                targetBlock.x,
                targetBlock.y,
                targetBlock.z,
                rotationIndex,
                blockType
        );

        tailoringState.addWindow(uuid, window);

        boolean opened = player.getPageManager().setPageWithWindows(
                ref,
                store,
                Page.Bench,
                true,
                new Window[]{window}
        );

        if (!opened) {
            tailoringState.removeWindow(uuid, window);
            return;
        }

        window.registerCloseEvent(event -> {
            tailoringState.removeWindow(uuid, window);

            BlockType currentBlockType = world.getBlockType(targetBlock);
            if (currentBlockType == null || currentBlockType == BlockType.EMPTY || currentBlockType == BlockType.UNKNOWN) {
                return;
            }

            if (blockType.getBench() != null && blockType.getBench().getLocalCloseSoundEventIndex() != 0) {
                SoundUtil.playSoundEvent2d(
                        ref,
                        blockType.getBench().getLocalCloseSoundEventIndex(),
                        SoundCategory.UI,
                        commandBuffer
                );
            }
        });

        if (blockType.getBench() != null && blockType.getBench().getLocalOpenSoundEventIndex() != 0) {
            SoundUtil.playSoundEvent2d(
                    ref,
                    blockType.getBench().getLocalOpenSoundEventIndex(),
                    SoundCategory.UI,
                    commandBuffer
            );
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
        CODEC = BuilderCodec.builder(
                        OpenArmorTailoringBenchInteraction.class,
                        OpenArmorTailoringBenchInteraction::new,
                        SimpleBlockInteraction.CODEC
                )
                .documentation("Opens the armor tailoring bench page.")
                .build();
    }
}