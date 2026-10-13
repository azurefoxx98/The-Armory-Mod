package me.ladypaladra.thearmorymod.blocks;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class BlockAccess {

    private BlockAccess() {
    }

    /**
     * Reads a loaded section without loading a chunk or limiting the block's height.
     */
    @Nullable
    public static BlockType getType(@Nonnull World world, int x, int y, int z) {
        ChunkStore chunkStore = world.getChunkStore();
        Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
        if (sectionRef == null || !sectionRef.isValid()) {
            return null;
        }

        BlockSection section = chunkStore.getStore().getComponent(sectionRef, BlockSection.getComponentType());
        if (section == null) {
            return null;
        }

        return BlockType.getAssetMap().getAsset(section.get(ChunkUtil.indexBlock(x, y, z)));
    }
}
