package me.ladypaladra.thearmorymod.krafter;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.BlockAccessor;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class MekanichalKrafterUtil {

    public static final String STATE_CLOSED = "KrafterClosed";
    public static final String STATE_OPEN = "KrafterOpen";

    private MekanichalKrafterUtil() {
    }

    public static boolean isKrafterBlock(@Nonnull BlockType blockType) {
        return blockType.getBlockForState(STATE_CLOSED) != null
                && blockType.getBlockForState(STATE_OPEN) != null;
    }

    public static boolean isState(@Nonnull BlockType blockType, @Nonnull String expectedState) {
        String currentState = BlockAccessor.getCurrentInteractionState(blockType);
        return expectedState.equals(currentState);
    }

    public static boolean setState(
            @Nonnull WorldChunk chunk,
            int x,
            int y,
            int z,
            @Nonnull String state
    ) {
        BlockType blockType = chunk.getBlockType(x, y, z);

        if (blockType == null || blockType.getData() == null) {
            return false;
        }

        BlockType newState = blockType.getBlockForState(state);

        if (newState == null) {
            return false;
        }

        chunk.setBlockInteractionState(x, y, z, blockType, state, true);
        return true;
    }

    @Nullable
    public static MekanichalKrafterBlock getKrafterComponent(
            @Nonnull World world,
            int x,
            int y,
            int z
    ) {
        return BlockModule.getComponent(
                MekanichalKrafterBlock.getComponentType(),
                world,
                x,
                y,
                z
        );
    }

    public static boolean restartCooldown(
            @Nonnull World world,
            @Nonnull WorldChunk chunk,
            int x,
            int y,
            int z
    ) {
        MekanichalKrafterBlock component = getKrafterComponent(world, x, y, z);

        if (component == null) {
            return false;
        }

        component.restartCooldown();

        boolean stateChanged = setState(chunk, x, y, z, STATE_CLOSED);

        markBlockEntityNeedsSaving(world, x, y, z);

        return stateChanged;
    }

    public static void markBlockEntityNeedsSaving(
            @Nonnull World world,
            int x,
            int y,
            int z
    ) {
        Ref<ChunkStore> blockRef = BlockModule.getBlockEntity(world, x, y, z);

        if (blockRef == null || !blockRef.isValid()) {
            return;
        }

        Store<ChunkStore> chunkStore = world.getChunkStore().getStore();

        BlockModule.BlockStateInfo blockStateInfo = chunkStore.getComponent(
                blockRef,
                BlockModule.BlockStateInfo.getComponentType()
        );

        if (blockStateInfo != null) {
            blockStateInfo.markNeedsSaving(chunkStore);
        }
    }
}