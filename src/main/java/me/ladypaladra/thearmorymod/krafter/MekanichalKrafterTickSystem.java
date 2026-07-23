package me.ladypaladra.thearmorymod.krafter;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;

public class MekanichalKrafterTickSystem extends EntityTickingSystem<ChunkStore> {

    @Nonnull
    private final ComponentType<ChunkStore, MekanichalKrafterBlock> krafterComponentType;

    @Nonnull
    private final Archetype<ChunkStore> archetype;

    public MekanichalKrafterTickSystem(
            @Nonnull ComponentType<ChunkStore, MekanichalKrafterBlock> krafterComponentType
    ) {
        this.krafterComponentType = krafterComponentType;
        this.archetype = Archetype.of(
                krafterComponentType,
                BlockModule.BlockStateInfo.getComponentType()
        );
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return archetype;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
            @Nonnull Store<ChunkStore> store,
            @Nonnull CommandBuffer<ChunkStore> commandBuffer
    ) {
        MekanichalKrafterBlock krafter = archetypeChunk.getComponent(index, krafterComponentType);

        BlockModule.BlockStateInfo blockStateInfo = archetypeChunk.getComponent(
                index,
                BlockModule.BlockStateInfo.getComponentType()
        );

        if (krafter == null || blockStateInfo == null) {
            return;
        }

        Ref<ChunkStore> chunkRef = blockStateInfo.getChunkRef();

        if (!chunkRef.isValid()) {
            return;
        }

        WorldChunk chunk = store.getComponent(chunkRef, WorldChunk.getComponentType());

        if (chunk == null) {
            return;
        }

        int blockIndex = blockStateInfo.getIndex();

        int x = ChunkUtil.xFromBlockInColumn(blockIndex);
        int y = ChunkUtil.yFromBlockInColumn(blockIndex);
        int z = ChunkUtil.zFromBlockInColumn(blockIndex);

        BlockType blockType = chunk.getBlockType(x, y, z);

        if (blockType == null || !MekanichalKrafterUtil.isKrafterBlock(blockType)) {
            return;
        }

        if (krafter.isReady()) {
            if (!MekanichalKrafterUtil.isState(blockType, MekanichalKrafterUtil.STATE_OPEN)) {
                MekanichalKrafterUtil.setState(
                        chunk,
                        x,
                        y,
                        z,
                        MekanichalKrafterUtil.STATE_OPEN
                );

                blockStateInfo.markNeedsSaving(store);
            }

            return;
        }

        if (!MekanichalKrafterUtil.isState(blockType, MekanichalKrafterUtil.STATE_CLOSED)) {
            MekanichalKrafterUtil.setState(
                    chunk,
                    x,
                    y,
                    z,
                    MekanichalKrafterUtil.STATE_CLOSED
            );

            blockStateInfo.markNeedsSaving(store);
        }

        boolean shouldSave = krafter.tickCooldown(dt);

        if (!shouldSave) {
            return;
        }

        if (krafter.isReady()) {
            MekanichalKrafterUtil.setState(
                    chunk,
                    x,
                    y,
                    z,
                    MekanichalKrafterUtil.STATE_OPEN
            );
        }

        blockStateInfo.markNeedsSaving(store);
    }
}