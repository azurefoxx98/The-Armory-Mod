package me.ladypaladra.thearmorymod.powers;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockBreakingDropType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.modules.interaction.BlockInteractionUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.blocks.BlockAccess;
import me.ladypaladra.thearmorymod.stats.ArmoryStatIds;
import me.ladypaladra.thearmorymod.stats.LazyStatIndex;
import me.ladypaladra.thearmorymod.stats.StatMultiplier;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Ore Pack's two powers: mining stone and ore faster, and a chance of one more ore.
 *
 * <p>Both are read from stats, so the engine sums them from whatever the player wears and the
 * tooltip shows them. Vanilla ore blocks all use a gather type that starts with Ore, apart from
 * one rock typed ore block whose id still starts with Ore_, and their ore items all have ids
 * starting with Ore_. Those two names are what the checks below rely on.</p>
 */
public final class OrePackSystems {

    private OrePackSystems() {
    }

    @Nullable
    static String gatherType(@Nullable BlockType blockType) {
        if (blockType == null) {
            return null;
        }
        BlockGathering gathering = blockType.getGathering();
        BlockBreakingDropType breaking = gathering != null ? gathering.getBreaking() : null;
        return breaking != null ? breaking.getGatherType() : null;
    }

    static boolean isOre(@Nullable BlockType blockType) {
        String gatherType = gatherType(blockType);
        return (gatherType != null && gatherType.startsWith("Ore"))
                || (blockType != null && blockType.getId() != null && blockType.getId().startsWith("Ore_"));
    }

    static boolean isStoneOrOre(@Nullable BlockType blockType) {
        String gatherType = gatherType(blockType);
        return "Rocks".equals(gatherType) || "VolcanicRocks".equals(gatherType) || isOre(blockType);
    }

    /**
     * Raises the damage each hit deals to stone and ore.
     */
    public static final class MiningSpeed extends EntityEventSystem<EntityStore, DamageBlockEvent> {

        private final LazyStatIndex miningSpeedIndex = new LazyStatIndex(ArmoryStatIds.MINING_SPEED);

        private final Query<EntityStore> query = Archetype.of(
                PlayerRef.getComponentType(),
                EntityStatMap.getComponentType()
        );

        public MiningSpeed() {
            super(DamageBlockEvent.class);
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public void handle(
                int index,
                @Nonnull ArchetypeChunk<EntityStore> chunk,
                @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull DamageBlockEvent event
        ) {
            if (event.isCancelled() || !isStoneOrOre(event.getBlockType())) {
                return;
            }

            float multiplier = StatMultiplier.forStat(chunk.getComponent(index, EntityStatMap.getComponentType()), miningSpeedIndex.get());
            if (multiplier > 1.0F + StatMultiplier.EPSILON) {
                event.setDamage(event.getDamage() * multiplier);
            }
        }
    }

    /**
     * Gives a chance of one extra ore when an ore block breaks.
     *
     * <p>The break event comes before the block is removed and another plugin may still cancel
     * it, a land claim for example. So the bonus is decided here but only paid on the world
     * thread afterwards, and only if the ore block is really gone. A cancelled break never pays.
     * The bonus is the block's own drops rolled once more, keeping only the ore, so it never
     * adds cobble. Breaks outside Adventure mode drop nothing, as in the engine.</p>
     */
    public static final class OreFind extends EntityEventSystem<EntityStore, BreakBlockEvent> {

        private final LazyStatIndex oreFindIndex = new LazyStatIndex(ArmoryStatIds.ORE_FIND);

        private final Query<EntityStore> query = Archetype.of(
                PlayerRef.getComponentType(),
                EntityStatMap.getComponentType()
        );

        public OreFind() {
            super(BreakBlockEvent.class);
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public void handle(
                int index,
                @Nonnull ArchetypeChunk<EntityStore> chunk,
                @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull BreakBlockEvent event
        ) {
            if (event.isCancelled()) {
                return;
            }

            BlockType blockType = event.getBlockType();
            if (!isOre(blockType)) {
                return;
            }

            float chance = StatMultiplier.forStat(chunk.getComponent(index, EntityStatMap.getComponentType()), oreFindIndex.get()) - 1.0F;
            if (chance <= StatMultiplier.EPSILON || ThreadLocalRandom.current().nextFloat() >= Math.min(chance, 1.0F)) {
                return;
            }

            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            if (!BlockInteractionUtils.isNaturalAction(ref, commandBuffer)) {
                return;
            }

            World world = store.getExternalData().getWorld();
            if (world == null) {
                return;
            }

            Vector3i at = new Vector3i(event.getTargetBlock());
            String oreBlockId = blockType.getId();
            world.execute(() -> payBonus(world, at, blockType, oreBlockId));
        }

        private static void payBonus(@Nonnull World world, @Nonnull Vector3i at, @Nonnull BlockType blockType, String oreBlockId) {
            BlockType now = BlockAccess.getType(world, at.x, at.y, at.z);
            if (now != null && oreBlockId != null && oreBlockId.equals(now.getId())) {
                return;
            }

            BlockGathering gathering = blockType.getGathering();
            BlockBreakingDropType breaking = gathering != null ? gathering.getBreaking() : null;
            if (breaking == null) {
                return;
            }

            List<ItemStack> ores = new ArrayList<>();
            for (ItemStack drop : BlockHarvestUtils.getDrops(blockType, 1, breaking.getItemId(), breaking.getDropListId())) {
                if (drop != null && drop.getItemId() != null && drop.getItemId().startsWith("Ore_")) {
                    ores.add(drop);
                }
            }
            if (ores.isEmpty()) {
                return;
            }

            Store<EntityStore> entities = world.getEntityStore().getStore();
            Holder<EntityStore>[] holders = ItemComponent.generateItemDrops(
                    entities,
                    ores,
                    new Vector3d(at.x + 0.5, at.y, at.z + 0.5),
                    Rotation3f.IDENTITY
            );
            entities.addEntities(holders, AddReason.SPAWN);
        }
    }
}
