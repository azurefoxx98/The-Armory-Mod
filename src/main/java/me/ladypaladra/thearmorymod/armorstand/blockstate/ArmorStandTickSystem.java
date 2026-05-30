package me.ladypaladra.thearmorymod.armorstand.blockstate;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerBlockWindow;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.ArmorSlotAddFilter;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.RoleUtils;
import com.hypixel.hytale.server.npc.util.InventoryHelper;
import com.hypixel.hytale.protocol.ItemArmorSlot;

import me.ladypaladra.thearmorymod.armorstand.ArmorStandModule;

import org.joml.Vector3d;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * ArmorStandTickSystem — ticks all ArmorStand blocks.
 *
 * Uses BlockStateInfo to resolve the block world position directly from ECS.
 * This lets mannequins spawn correctly on world load without needing a player
 * to open the container first.
 */
@SuppressWarnings({"removal", "deprecation"})
public class ArmorStandTickSystem extends EntityTickingSystem<ChunkStore> {

    private static final Logger LOGGER = Logger.getLogger("TheArmory-ArmorStand");
    private static final String MANNEQUIN_ROLE = "ArmorStand_Mannequin";

    /**
     * Transient per-block runtime state.
     * Keyed by packed world position using BlockUtil.pack(x, y, z).
     */
    private static final Map<Long, BlockRuntime> runtimeMap = new ConcurrentHashMap<>();

    private final ComponentType<ChunkStore, ArmorStandComponent> componentType;
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockStateInfoComponentType;
    private final Query<ChunkStore> query;

    public ArmorStandTickSystem(ComponentType<ChunkStore, ArmorStandComponent> componentType) {
        this.componentType = componentType;
        this.blockStateInfoComponentType = BlockModule.BlockStateInfo.getComponentType();
        this.query = Query.and(componentType, blockStateInfoComponentType);
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float dt, int index, ArchetypeChunk<ChunkStore> chunk,
                     Store<ChunkStore> store, CommandBuffer<ChunkStore> commandBuffer) {

        ArmorStandComponent armorComp = chunk.getComponent(index, componentType);
        BlockModule.BlockStateInfo blockInfo = chunk.getComponent(index, blockStateInfoComponentType);
        ItemContainerBlock containerBlock = chunk.getComponent(index, ItemContainerBlock.getComponentType());

        if (armorComp == null || blockInfo == null || containerBlock == null) return;

        SimpleItemContainer container = containerBlock.getItemContainer();
        if (container == null) return;

        Ref<ChunkStore> chunkRef = blockInfo.getChunkRef();
        if (chunkRef == null || !chunkRef.isValid()) return;

        int packedIndex = blockInfo.getIndex();

        int localX = ChunkUtil.xFromBlockInColumn(packedIndex);
        int worldY = ChunkUtil.yFromBlockInColumn(packedIndex);
        int localZ = ChunkUtil.zFromBlockInColumn(packedIndex);

        BlockChunk blockChunk = commandBuffer.getComponent(chunkRef, BlockChunk.getComponentType());
        if (blockChunk == null) return;

        int worldX = blockChunk.getX() * ChunkUtil.SIZE + localX;
        int worldZ = blockChunk.getZ() * ChunkUtil.SIZE + localZ;

        float yawRad = 0.0f;

        try {
            BlockSection section = blockChunk.getSectionAtBlockY(worldY);

            if (section != null) {
                int localYInSection = worldY % ChunkUtil.SIZE;
                RotationTuple rot = section.getRotation(localX, localYInSection, localZ);

                if (rot != null && rot.yaw() != null) {
                    yawRad = (float) rot.yaw().getRadians();
                }
            }
        } catch (Exception ignored) {}

        World world;

        try {
            world = store.getExternalData().getWorld();
        } catch (Exception e) {
            return;
        }

        if (world == null) return;

        long posKey = com.hypixel.hytale.math.block.BlockUtil.pack(worldX, worldY, worldZ);

        BlockRuntime rt = runtimeMap.computeIfAbsent(posKey, k -> new BlockRuntime());

        rt.worldX = worldX;
        rt.worldY = worldY;
        rt.worldZ = worldZ;
        rt.yawRadians = yawRad;

        if (!rt.filtersApplied) {
            applySlotFilters(container);
            rt.filtersApplied = true;
        }

        Map<UUID, ContainerBlockWindow> windows = containerBlock.getWindows();
        boolean isOpen = windows != null && !windows.isEmpty();

        if (rt.wasOpen && !isOpen) {
            updateMannequin(armorComp, container, rt, world);
        }

        if (rt.healDelayTicks > 0) {
            rt.healDelayTicks--;
        } else if (rt.healDelayTicks == 0) {
            rt.healDelayTicks = -1;

            if (rt.mannequin != null) {
                world.execute(() -> healToFull(rt.mannequin));
            }
        }

        if (!rt.firstTickDone) {
            rt.firstTickDone = true;

            UUID persistedUuid = armorComp.getMannequinUuid();
            String hash = buildArmorHash(container);
            boolean shouldRespawn = !hash.isEmpty();

            if (persistedUuid != null) {
                armorComp.setMannequinUuid(null);

                if (shouldRespawn) {
                    rt.mannequinSpawned = true;
                    rt.lastArmorHash = hash;
                }

                final boolean doRespawn = shouldRespawn;
                final ArmorStandComponent comp = armorComp;
                final SimpleItemContainer cont = container;
                final BlockRuntime rtRef = rt;

                world.execute(() -> {
                    try {
                        Entity old = world.getEntity(persistedUuid);

                        if (old != null) {
                            if (old instanceof NPCEntity oldNpc) {
                                ArmorStandModule.untrackMannequin(oldNpc);
                            }

                            old.remove();
                        }
                    } catch (Exception e) {
                        LOGGER.warning("Failed to remove persisted mannequin: " + e.getMessage());
                    }

                    if (doRespawn) {
                        spawnMannequinSync(comp, cont, rtRef, world);
                    }
                });

                if (shouldRespawn) {
                    rt.healDelayTicks = 10;
                }
            } else if (shouldRespawn) {
                updateMannequin(armorComp, container, rt, world);
            }
        }

        if (isOpen && rt.mannequin != null) {
            despawnMannequin(rt, world);
        }

        rt.wasOpen = isOpen;
    }

    private void applySlotFilters(SimpleItemContainer container) {
        try {
            container.setSlotFilter(
                    FilterActionType.ADD,
                    (short) 0,
                    new ArmorSlotAddFilter(ItemArmorSlot.Head)
            );

            container.setSlotFilter(
                    FilterActionType.ADD,
                    (short) 1,
                    new ArmorSlotAddFilter(ItemArmorSlot.Chest)
            );

            container.setSlotFilter(
                    FilterActionType.ADD,
                    (short) 2,
                    new ArmorSlotAddFilter(ItemArmorSlot.Hands)
            );

            container.setSlotFilter(
                    FilterActionType.ADD,
                    (short) 3,
                    new ArmorSlotAddFilter(ItemArmorSlot.Legs)
            );
        } catch (Exception e) {
            LOGGER.warning("Could not apply armor slot filters: " + e.getMessage());
        }
    }

    private String buildArmorHash(SimpleItemContainer container) {
        StringBuilder sb = new StringBuilder();

        for (short i = 0; i < Math.min(container.getCapacity(), (short) 6); i++) {
            ItemStack stack = container.getItemStack(i);

            if (stack != null && !stack.isEmpty()) {
                sb.append(i)
                        .append("=")
                        .append(stack.getItem().getId())
                        .append(";");
            }
        }

        return sb.toString();
    }

    private void updateMannequin(ArmorStandComponent armorComp, SimpleItemContainer container,
                                 BlockRuntime rt, World world) {
        try {
            String currentHash = buildArmorHash(container);

            if (currentHash.equals(rt.lastArmorHash) && rt.mannequinSpawned) {
                return;
            }

            despawnMannequin(rt, world);

            if (currentHash.isEmpty()) {
                rt.lastArmorHash = currentHash;
                return;
            }

            rt.mannequinSpawned = true;
            rt.lastArmorHash = currentHash;

            world.execute(() -> spawnMannequinSync(armorComp, container, rt, world));

            rt.healDelayTicks = 10;
        } catch (Exception e) {
            LOGGER.warning("Error updating mannequin: " + e.getMessage());
        }
    }

    private void spawnMannequinSync(ArmorStandComponent armorComp, SimpleItemContainer container,
                                    BlockRuntime rt, World world) {
        try {
            Vector3d blockPos = new Vector3d(
                    rt.worldX + 0.5,
                    rt.worldY,
                    rt.worldZ + 0.5
            );

            float yawRad = rt.yawRadians + (float) Math.PI;

            NPCEntity npc = new NPCEntity(world);
            npc.setRoleName(MANNEQUIN_ROLE);

            Rotation3f rotation = new Rotation3f(0.0f, yawRad, 0.0f);

            NPCEntity spawned = world.spawnEntity(npc, blockPos, rotation);

            if (spawned != null) {
                equipFromContainer(spawned, container);

                rt.mannequin = spawned;
                armorComp.setMannequinUuid(spawned.getUuid());

                ArmorStandModule.trackMannequin(spawned);
            } else {
                rt.mannequinSpawned = false;
                rt.lastArmorHash = "";
            }
        } catch (Exception e) {
            rt.mannequinSpawned = false;
            rt.lastArmorHash = "";

            LOGGER.warning("Failed to spawn mannequin: " + e.getMessage());
        }
    }

    private void equipFromContainer(NPCEntity npc, SimpleItemContainer container) {
        Ref<EntityStore> npcRef = npc.getReference();

        if (npcRef == null || !npcRef.isValid()) return;

        Store<EntityStore> entityStore = npcRef.getStore();
        if (entityStore == null) return;

        for (short i = 0; i < Math.min(container.getCapacity(), (short) 4); i++) {
            ItemStack stack = container.getItemStack(i);

            if (stack != null && !stack.isEmpty()) {
                RoleUtils.setArmor(npcRef, npc, stack.getItem().getId(), entityStore);
            }
        }

        if (container.getCapacity() > 4) {
            ItemStack mainHand = container.getItemStack((short) 4);

            if (mainHand != null && !mainHand.isEmpty()) {
                try {
                    RoleUtils.setItemInHand(npcRef, npc, mainHand.getItem().getId(), entityStore);
                } catch (Exception e) {
                    LOGGER.warning("Could not set main hand item: " + e.getMessage());
                }
            }
        }

        if (container.getCapacity() > 5) {
            ItemStack offHand = container.getItemStack((short) 5);

            if (offHand != null && !offHand.isEmpty()) {
                try {
                    InventoryHelper.setOffHandItem(
                            npcRef,
                            offHand.getItem().getId(),
                            (byte) 0,
                            entityStore
                    );
                } catch (Exception e) {
                    LOGGER.warning("Could not set off hand item: " + e.getMessage());
                }
            }
        }
    }

    private void healToFull(NPCEntity npc) {
        try {
            Ref<EntityStore> npcRef = npc.getReference();

            if (npcRef == null || !npcRef.isValid()) return;

            Store<EntityStore> store = npcRef.getStore();

            EntityStatMap statMap = store.getComponent(npcRef, EntityStatMap.getComponentType());
            if (statMap == null) return;

            EntityStatValue health = statMap.get(DefaultEntityStatTypes.getHealth());

            if (health != null) {
                statMap.setStatValue(DefaultEntityStatTypes.getHealth(), health.getMax());
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to heal mannequin: " + e.getMessage());
        }
    }

    private void despawnMannequin(BlockRuntime rt, World world) {
        if (rt.mannequin != null) {
            NPCEntity npc = rt.mannequin;

            ArmorStandModule.untrackMannequin(npc);

            world.execute(() -> {
                try {
                    npc.remove();
                } catch (Exception ignored) {}
            });

            rt.mannequin = null;
            rt.mannequinSpawned = false;
            rt.lastArmorHash = "";
        }
    }

    public static void cleanupBlock(long posKey, World world) {
        BlockRuntime rt = runtimeMap.remove(posKey);

        if (rt != null && rt.mannequin != null) {
            NPCEntity npc = rt.mannequin;

            ArmorStandModule.untrackMannequin(npc);

            world.execute(() -> {
                try {
                    npc.remove();
                } catch (Exception ignored) {}
            });
        }
    }

    private static class BlockRuntime {
        NPCEntity mannequin = null;

        boolean mannequinSpawned = false;
        boolean filtersApplied = false;
        boolean wasOpen = false;
        boolean firstTickDone = false;

        String lastArmorHash = "";

        int healDelayTicks = -1;

        int worldX = 0;
        int worldY = 0;
        int worldZ = 0;

        float yawRadians = 0.0f;
    }
}