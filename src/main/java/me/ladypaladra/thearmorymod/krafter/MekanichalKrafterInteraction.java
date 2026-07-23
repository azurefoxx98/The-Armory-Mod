package me.ladypaladra.thearmorymod.krafter;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

public class MekanichalKrafterInteraction extends SimpleBlockInteraction {

    @Nonnull
    public static final BuilderCodec<MekanichalKrafterInteraction> CODEC;

    private Mode mode = Mode.CHECK_OPEN;

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
        WorldChunk chunk = getWorldChunk(world, targetBlock);

        if (chunk == null) {
            fail(context);
            return;
        }

        BlockType blockType = chunk.getBlockType(targetBlock);

        if (blockType == null || !MekanichalKrafterUtil.isKrafterBlock(blockType)) {
            fail(context);
            return;
        }

        MekanichalKrafterBlock krafter = MekanichalKrafterUtil.getKrafterComponent(
                world,
                targetBlock.x,
                targetBlock.y,
                targetBlock.z
        );

        if (krafter == null) {
            fail(context);
            return;
        }

        if (mode == Mode.CHECK_OPEN) {
            if (
                    !krafter.isReady()
                            || !MekanichalKrafterUtil.isState(blockType, MekanichalKrafterUtil.STATE_OPEN)
            ) {
                fail(context);
            }

            return;
        }

        if (mode == Mode.CONSUME_AND_RESTART) {
            if (
                    !krafter.isReady()
                            || !MekanichalKrafterUtil.isState(blockType, MekanichalKrafterUtil.STATE_OPEN)
            ) {
                fail(context);
                return;
            }

            boolean restarted = MekanichalKrafterUtil.restartCooldown(
                    world,
                    chunk,
                    targetBlock.x,
                    targetBlock.y,
                    targetBlock.z
            );

            if (!restarted) {
                fail(context);
            }
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

    private static WorldChunk getWorldChunk(@Nonnull World world, @Nonnull Vector3i block) {
        return getWorldChunk(world, block.x, block.z);
    }

    @Nullable
    private static WorldChunk getWorldChunk(@Nonnull World world, int blockX, int blockZ) {
        ChunkStore chunkStore = world.getChunkStore();
        long chunkIndex = ChunkUtil.indexChunkFromBlock(blockX, blockZ);
        Ref<ChunkStore> chunkReference = chunkStore.getChunkReference(chunkIndex);

        if (chunkReference == null || !chunkReference.isValid()) {
            return null;
        }

        return chunkStore.getStore().getComponent(
                chunkReference,
                WorldChunk.getComponentType()
        );
    }

    private static void fail(@Nonnull InteractionContext context) {
        context.getState().state = InteractionState.Failed;
    }

    private enum Mode {
        CHECK_OPEN,
        CONSUME_AND_RESTART;

        static Mode fromString(String value) {
            if (value == null) {
                return CHECK_OPEN;
            }

            String normalized = value.trim().toLowerCase(Locale.ROOT);

            if (
                    normalized.equals("consume")
                            || normalized.equals("consumeandrestart")
                            || normalized.equals("consume_and_restart")
                            || normalized.equals("consume-and-restart")
            ) {
                return CONSUME_AND_RESTART;
            }

            return CHECK_OPEN;
        }

        String toAssetValue() {
            if (this == CONSUME_AND_RESTART) {
                return "ConsumeAndRestart";
            }

            return "CheckOpen";
        }
    }

    static {
        CODEC = BuilderCodec.builder(
                        MekanichalKrafterInteraction.class,
                        MekanichalKrafterInteraction::new,
                        SimpleBlockInteraction.CODEC
                )
                .documentation("Checks and consumes the Mekanichal Krafter open state.")
                .appendInherited(
                        new KeyedCodec<>("Mode", Codec.STRING),
                        (interaction, value) -> interaction.mode = Mode.fromString(value),
                        interaction -> interaction.mode.toAssetValue(),
                        (interaction, parent) -> interaction.mode = parent.mode
                )
                .documentation("Interaction mode. Use CheckOpen or ConsumeAndRestart.")
                .add()
                .build();
    }
}