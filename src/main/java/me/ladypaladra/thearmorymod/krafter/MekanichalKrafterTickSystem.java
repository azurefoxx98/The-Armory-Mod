package me.ladypaladra.thearmorymod.krafter;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3i;

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

        Ref<ChunkStore> sectionRef = blockStateInfo.getSectionRef();

        if (!sectionRef.isValid()) {
            return;
        }

        BlockSection blockSection = store.getComponent(sectionRef, BlockSection.getComponentType());

        if (blockSection == null) {
            return;
        }

        int blockIndex = blockStateInfo.getIndex();
        BlockType blockType = BlockType.getAssetMap().getAsset(blockSection.get(blockIndex));

        Vector3i blockPosition = new Vector3i();

        if (blockType == null
                || !MekanichalKrafterUtil.isKrafterBlock(blockType)
                || !blockStateInfo.fillWorldPos(store, blockPosition)) {
            return;
        }

        if (krafter.isReady()) {
            if (!MekanichalKrafterUtil.isState(blockType, MekanichalKrafterUtil.STATE_OPEN)) {
                BlockOperations.setBlockInteractionState(
                        store.getExternalData(),
                        sectionRef,
                        blockPosition.x,
                        blockPosition.y,
                        blockPosition.z,
                        blockType,
                        MekanichalKrafterUtil.STATE_OPEN,
                        false
                );

                blockStateInfo.markNeedsSaving(store);
            }

            return;
        }

        if (!MekanichalKrafterUtil.isState(blockType, MekanichalKrafterUtil.STATE_CLOSED)) {
            BlockOperations.setBlockInteractionState(
                    store.getExternalData(),
                    sectionRef,
                    blockPosition.x,
                    blockPosition.y,
                    blockPosition.z,
                    blockType,
                    MekanichalKrafterUtil.STATE_CLOSED,
                    false
            );

            blockStateInfo.markNeedsSaving(store);
        }

        boolean shouldSave = krafter.tickCooldown(dt);

        if (!shouldSave) {
            return;
        }

        if (krafter.isReady()) {
            BlockType currentBlockType = BlockType.getAssetMap().getAsset(blockSection.get(blockIndex));

            if (currentBlockType != null
                    && !MekanichalKrafterUtil.isState(currentBlockType, MekanichalKrafterUtil.STATE_OPEN)) {
                BlockOperations.setBlockInteractionState(
                        store.getExternalData(),
                        sectionRef,
                        blockPosition.x,
                        blockPosition.y,
                        blockPosition.z,
                        currentBlockType,
                        MekanichalKrafterUtil.STATE_OPEN,
                        false
                );
            }
        }

        blockStateInfo.markNeedsSaving(store);
    }
}
