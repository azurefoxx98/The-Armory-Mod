package me.ladypaladra.thearmorymod.krafter;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import org.joml.Vector3i;

import javax.annotation.Nonnull;

public class MekanichalKrafterDispenserTickSystem extends EntityTickingSystem<ChunkStore> {

    @Nonnull
    private final ComponentType<ChunkStore, MekanichalKrafterDispenser> dispenserComponentType;

    @Nonnull
    private final Archetype<ChunkStore> archetype;

    public MekanichalKrafterDispenserTickSystem(
            @Nonnull ComponentType<ChunkStore, MekanichalKrafterDispenser> dispenserComponentType
    ) {
        this.dispenserComponentType = dispenserComponentType;
        this.archetype = Archetype.of(
                dispenserComponentType,
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
        MekanichalKrafterDispenser dispenser = archetypeChunk.getComponent(index, dispenserComponentType);

        BlockModule.BlockStateInfo blockStateInfo = archetypeChunk.getComponent(
                index,
                BlockModule.BlockStateInfo.getComponentType()
        );

        if (dispenser == null || blockStateInfo == null) {
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

        BlockType blockType = BlockType.getAssetMap().getAsset(blockSection.get(blockStateInfo.getIndex()));
        Vector3i position = new Vector3i();

        if (blockType == null || !blockStateInfo.fillWorldPos(store, position)) {
            return;
        }

        World world = store.getExternalData().getWorld();

        if (world == null) {
            return;
        }

        int deltaMillis = Math.max(1, (int) (dt * 1000.0F));
        MekanichalKrafterDispenser.Step step = dispenser.advance(deltaMillis);
        boolean changed = step != MekanichalKrafterDispenser.Step.NONE;

        int x = position.x;
        int y = position.y;
        int z = position.z;

        if (step == MekanichalKrafterDispenser.Step.DUE) {
            if (isFree(world, x, y + topOffset(blockType), z)) {
                dispenser.beginDispense();
                world.execute(() -> MekanichalKrafterUtil.setState(world, x, y, z, MekanichalKrafterDispenser.STATE_DISPENSE));
            }
        } else if (step == MekanichalKrafterDispenser.Step.FINISHED) {
            world.execute(() -> finishDispense(world, x, y, z));
        } else if (!dispenser.isDispensing()
                && MekanichalKrafterUtil.isState(blockType, MekanichalKrafterDispenser.STATE_DISPENSE)) {
            // A restart can save the block in its Dispense state after the clock already moved on.
            world.execute(() -> MekanichalKrafterUtil.setState(world, x, y, z, StateData.NULL_STATE_ID));
        }

        if (changed || dispenser.takeSaveDue()) {
            blockStateInfo.markNeedsSaving(store);
        }
    }

    // Runs on the world thread after the tick, so the krafter is read again: it may have been broken meanwhile.
    private static void finishDispense(@Nonnull World world, int x, int y, int z) {
        BlockType krafterType = MekanichalKrafterUtil.dispenserType(world, x, y, z);

        if (krafterType == null) {
            return;
        }

        int targetY = y + topOffset(krafterType);

        if (isFree(world, x, targetY, z)) {
            place(world, x, targetY, z, MekanichalKrafterDispenser.DISPENSED_BLOCK_ID);
        }

        MekanichalKrafterUtil.setState(world, x, y, z, StateData.NULL_STATE_ID);
    }

    /**
     * How many cells above its origin the krafter's hitbox ends, so the dispensed block lands on its top
     * whatever size the model is drawn at.
     */
    static int topOffset(@Nonnull BlockType blockType) {
        BlockBoundingBoxes boxes = BlockBoundingBoxes.getAssetMap().getAsset(blockType.getHitboxTypeIndex());

        if (boxes == null) {
            return 1;
        }

        return Math.max(1, (int) Math.ceil(boxes.get(0).getBoundingBox().getMax().y));
    }

    // Free means no block and no fluid, and a chunk that is loaded: an unloaded cell is never treated as free.
    static boolean isFree(@Nonnull World world, int x, int y, int z) {
        ChunkStore chunkStore = world.getChunkStore();
        Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);

        if (sectionRef == null || !sectionRef.isValid()) {
            return false;
        }

        BlockSection blocks = chunkStore.getStore().getComponent(sectionRef, BlockSection.getComponentType());
        FluidSection fluids = chunkStore.getStore().getComponent(sectionRef, FluidSection.getComponentType());

        if (blocks == null || fluids == null) {
            return false;
        }

        return blocks.get(x, y, z) == BlockType.EMPTY_ID && fluids.getFluidId(x, y, z) == Fluid.EMPTY_ID;
    }

    private static void place(@Nonnull World world, int x, int y, int z, @Nonnull String blockId) {
        BlockType type = BlockType.getAssetMap().getAsset(blockId);

        if (type == null) {
            return;
        }

        int id = BlockType.getAssetMap().getIndex(blockId);
        ChunkStore chunkStore = world.getChunkStore();
        Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);

        if (sectionRef == null || !sectionRef.isValid()) {
            return;
        }

        BlockOperations.setBlock(
                chunkStore,
                sectionRef,
                x,
                y,
                z,
                id,
                type,
                0,
                FillerBlockUtil.NO_FILLER,
                SetBlockSettings.PERFORM_BLOCK_UPDATE
        );
    }
}
