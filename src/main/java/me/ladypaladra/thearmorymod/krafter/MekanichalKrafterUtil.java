package me.ladypaladra.thearmorymod.krafter;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import me.ladypaladra.thearmorymod.blocks.BlockAccess;

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
        String currentState = blockType.getCurrentInteractionState();
        return expectedState.equals(currentState);
    }

    public static boolean setState(
            @Nonnull World world,
            int x,
            int y,
            int z,
            @Nonnull String state
    ) {
        ChunkStore chunkStore = world.getChunkStore();
        Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
        if (sectionRef == null || !sectionRef.isValid()) {
            return false;
        }

        BlockType blockType = BlockAccess.getType(world, x, y, z);

        if (blockType == null || blockType.getData() == null) {
            return false;
        }

        BlockType newState = blockType.getBlockForState(state);

        if (newState == null) {
            return false;
        }

        BlockOperations.setBlockInteractionState(chunkStore, sectionRef, x, y, z, blockType, state, true);
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
            int x,
            int y,
            int z
    ) {
        MekanichalKrafterBlock component = getKrafterComponent(world, x, y, z);

        if (component == null) {
            return false;
        }

        if (!setState(world, x, y, z, STATE_CLOSED)) {
            return false;
        }

        component.restartCooldown();

        markBlockEntityNeedsSaving(world, x, y, z);

        return true;
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
