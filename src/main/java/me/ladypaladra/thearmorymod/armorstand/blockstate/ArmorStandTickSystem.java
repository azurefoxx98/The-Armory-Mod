package me.ladypaladra.thearmorymod.armorstand.blockstate;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerBlockWindow;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.ArmorSlotAddFilter;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.RoleUtils;
import com.hypixel.hytale.server.npc.util.InventoryHelper;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.protocol.MovementStates;

import me.ladypaladra.thearmorymod.armorstand.ArmorStandModule;

import org.joml.Vector3d;

import it.unimi.dsi.fastutil.Pair;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final boolean ENABLE_MANNEQUIN_NPC = true;
    private static final boolean SAFE_MODE_DISABLE_HAND_ITEMS = false;

    // May cause a bug in the future?
    private static final Set<String> MANNEQUIN_ROLES = Set.of(
            "ArmorStand_Mannequin",
            "HubSocial:ArmorStand_Mannequin",
            "ArmorStandMod:ArmorStand_Mannequin"
    );

    private static final Set<String> MANNEQUIN_MODELS = Set.of(
            "ArmorStand_Mannequin",
            "HubSocial:ArmorStand_Mannequin",
            "ArmorStandMod:ArmorStand_Mannequin"
    );

    /**
     * Transient per-block runtime state.
     * Keyed by world name plus packed block position.
     */
    private static final Map<BlockRuntimeKey, BlockRuntime> runtimeMap = new ConcurrentHashMap<>();

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
    public void tick(float dt,
                     int index,
                     ArchetypeChunk<ChunkStore> chunk,
                     Store<ChunkStore> store,
                     CommandBuffer<ChunkStore> commandBuffer) {

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
        BlockRuntimeKey runtimeKey = runtimeKey(world, posKey);

        BlockRuntime rt = runtimeMap.computeIfAbsent(runtimeKey, k -> new BlockRuntime());

        rt.worldX = worldX;
        rt.worldY = worldY;
        rt.worldZ = worldZ;
        rt.yawRadians = yawRad;

        if (rt.mannequinRef != null && !isRuntimeMannequinUsable(rt.mannequinRef, world)) {
            ArmorStandModule.untrackMannequin(rt.mannequinRef);

            rt.mannequinRef = null;
            rt.mannequinSpawned = false;
            rt.lastArmorHash = "";
        }

        if (!rt.filtersApplied) {
            applySlotFilters(container);
            rt.filtersApplied = true;
        }

        Map<UUID, ContainerBlockWindow> windows = containerBlock.getWindows();
        boolean isOpen = windows != null && !windows.isEmpty();

        if (!ENABLE_MANNEQUIN_NPC) {
            if (rt.mannequinRef != null) {
                despawnMannequin(rt, world);
            }

            UUID persistedUuid = armorComp.getMannequinUuid();

            if (persistedUuid != null) {
                armorComp.setMannequinUuid(null);

                world.execute(() -> {
                    try {
                        removeMannequinByUuid(world, persistedUuid);
                    } catch (Exception e) {
                        LOGGER.warning(
                                "Failed to remove mannequin while NPC mode is disabled: "
                                        + e.getMessage()
                        );
                    }
                });
            }

            rt.mannequinSpawned = false;
            rt.lastArmorHash = "";
            rt.invalidEquipmentHash = "";
            rt.healDelayTicks = -1;
            rt.wasOpen = isOpen;

            return;
        }

        if (rt.wasOpen && !isOpen) {
            updateMannequin(armorComp, container, rt, world);
        }

        if (rt.healDelayTicks > 0) {
            rt.healDelayTicks--;
        } else if (rt.healDelayTicks == 0) {
            rt.healDelayTicks = -1;

            if (rt.mannequinRef != null) {
                Ref<EntityStore> mannequinRef = rt.mannequinRef;

                world.execute(() -> healToFull(mannequinRef));
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
                final String sourceHash = hash;

                world.execute(() -> {
                    try {
                        removeMannequinByUuid(world, persistedUuid);
                    } catch (Exception e) {
                        LOGGER.warning("Failed to remove persisted mannequin: " + e.getMessage());
                    }

                    if (doRespawn) {
                        spawnMannequinSync(comp, cont, rtRef, world, sourceHash);
                    }
                });

                if (shouldRespawn) {
                    rt.healDelayTicks = 10;
                }
            } else if (shouldRespawn) {
                updateMannequin(armorComp, container, rt, world);
            }
        } else if (!isOpen && !rt.mannequinSpawned && !buildArmorHash(container).isEmpty()) {
            updateMannequin(armorComp, container, rt, world);
        }

        if (isOpen && rt.mannequinRef != null) {
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

    private void updateMannequin(ArmorStandComponent armorComp,
                                 SimpleItemContainer container,
                                 BlockRuntime rt,
                                 World world) {
        try {
            String currentHash = buildArmorHash(container);

            if (currentHash.equals(rt.invalidEquipmentHash)) return;

            if (currentHash.equals(rt.lastArmorHash) && rt.mannequinSpawned) {
                return;
            }

            despawnMannequin(rt, world);

            if (currentHash.isEmpty()) {
                rt.lastArmorHash = currentHash;
                rt.invalidEquipmentHash = "";
                return;
            }

            rt.mannequinSpawned = true;
            rt.lastArmorHash = currentHash;

            world.execute(() -> spawnMannequinSync(armorComp, container, rt, world, currentHash));

            rt.healDelayTicks = 10;
        } catch (Exception e) {
            LOGGER.warning("Error updating mannequin: " + e.getMessage());
        }
    }

    private void spawnMannequinSync(ArmorStandComponent armorComp,
                                    SimpleItemContainer container,
                                    BlockRuntime rt,
                                    World world,
                                    String sourceHash) {
        try {
            Vector3d blockPos = new Vector3d(
                    rt.worldX + 0.5,
                    rt.worldY,
                    rt.worldZ + 0.5
            );

            float yawRad = rt.yawRadians + (float) Math.PI;

            NPCPlugin npcPlugin = NPCPlugin.get();

            if (npcPlugin == null || !npcPlugin.hasRoleName(MANNEQUIN_ROLE)) {
                rt.mannequinSpawned = false;
                rt.lastArmorHash = "";

                LOGGER.warning(
                        "ArmorStand mannequin role is not registered: "
                                + MANNEQUIN_ROLE
                                + " (loadout hash "
                                + sourceHash
                                + ")"
                );

                return;
            }

            Rotation3f rotation = new Rotation3f(0.0f, yawRad, 0.0f);

            Store<EntityStore> entityStore = world.getEntityStore().getStore();

            Pair<Ref<EntityStore>, ?> spawn =
                    npcPlugin.spawnNPC(entityStore, MANNEQUIN_ROLE, null, blockPos, rotation);

            Ref<EntityStore> spawnedRef = spawn != null ? spawn.first() : null;

            NPCEntity spawned =
                    spawnedRef != null && spawnedRef.isValid()
                            ? entityStore.getComponent(spawnedRef, NPCEntity.getComponentType())
                            : null;

            if (spawned != null) {
                removeDuplicateMannequinsAtBlock(entityStore, spawnedRef, blockPos);

                boolean equipped = equipFromContainer(spawned, container, rt, world);

                if (!equipped) {
                    try {
                        spawned.remove();
                    } catch (Exception ignored) {}

                    rt.mannequinSpawned = false;
                    rt.lastArmorHash = "";
                    rt.invalidEquipmentHash = sourceHash;

                    LOGGER.warning(
                            "ArmorStand mannequin hidden because equipment apply failed for loadout hash "
                                    + sourceHash
                                    + " (role="
                                    + MANNEQUIN_ROLE
                                    + ")"
                    );

                    return;
                }

                UUID spawnedUuid = spawned.getUuid();

                if (!stripNpcBehavior(spawnedRef, entityStore)) {
                    try {
                        spawned.remove();
                    } catch (Exception ignored) {}

                    rt.mannequinSpawned = false;
                    rt.lastArmorHash = "";
                    rt.invalidEquipmentHash = sourceHash;

                    LOGGER.warning(
                            "ArmorStand mannequin hidden because NPC behavior could not be stripped for loadout hash "
                                    + sourceHash
                                    + " (role="
                                    + MANNEQUIN_ROLE
                                    + ")"
                    );

                    return;
                }

                rt.invalidEquipmentHash = "";
                rt.mannequinRef = spawnedRef;

                armorComp.setMannequinUuid(spawnedUuid);

                ArmorStandModule.trackMannequin(spawnedRef);
            } else {
                rt.mannequinSpawned = false;
                rt.lastArmorHash = "";

                LOGGER.warning(
                        "ArmorStand mannequin spawn returned null for role "
                                + MANNEQUIN_ROLE
                                + " (loadout hash "
                                + sourceHash
                                + ")"
                );
            }
        } catch (Exception e) {
            rt.mannequinSpawned = false;
            rt.lastArmorHash = "";

            LOGGER.warning(
                    "Failed to spawn mannequin for role "
                            + MANNEQUIN_ROLE
                            + " (loadout hash "
                            + sourceHash
                            + "): "
                            + e.getMessage()
            );
        }
    }

    private boolean equipFromContainer(NPCEntity npc,
                                       SimpleItemContainer container,
                                       BlockRuntime rt,
                                       World world) {
        Ref<EntityStore> npcRef = npc.getReference();

        if (npcRef == null || !npcRef.isValid()) return false;

        Store<EntityStore> entityStore = npcRef.getStore();
        if (entityStore == null) return false;

        for (short i = 0; i < Math.min(container.getCapacity(), (short) 4); i++) {
            ItemStack stack = container.getItemStack(i);

            if (stack != null && !stack.isEmpty()) {
                try {
                    if (stack.getItem() == null
                            || stack.getItem().getId() == null
                            || stack.getItem().getId().isEmpty()) {
                        return false;
                    }

                    RoleUtils.setArmor(
                            npcRef,
                            npc,
                            stack.getItem().getId(),
                            entityStore
                    );
                } catch (Exception e) {
                    LOGGER.warning("Could not set armor slot " + i + ": " + e.getMessage());
                    return false;
                }
            }
        }

        if (!SAFE_MODE_DISABLE_HAND_ITEMS) {
            if (container.getCapacity() > 4) {
                ItemStack mainHand = container.getItemStack((short) 4);

                if (mainHand != null && !mainHand.isEmpty()) {
                    try {
                        if (mainHand.getItem() == null
                                || mainHand.getItem().getId() == null
                                || mainHand.getItem().getId().isEmpty()) {
                            LOGGER.warning("Could not set main hand item: missing item id");
                        } else {
                            RoleUtils.setItemInHand(
                                    npcRef,
                                    npc,
                                    mainHand.getItem().getId(),
                                    entityStore
                            );
                        }
                    } catch (Exception e) {
                        LOGGER.warning("Could not set main hand item: " + e.getMessage());
                    }
                }
            }

            if (container.getCapacity() > 5) {
                ItemStack offHand = container.getItemStack((short) 5);

                if (offHand != null && !offHand.isEmpty()) {
                    try {
                        if (offHand.getItem() == null
                                || offHand.getItem().getId() == null
                                || offHand.getItem().getId().isEmpty()) {
                            LOGGER.warning("Could not set off hand item: missing item id");
                        } else {
                            InventoryHelper.setOffHandItem(
                                    npcRef,
                                    offHand.getItem().getId(),
                                    (byte) 0,
                                    entityStore
                            );
                        }
                    } catch (Exception e) {
                        LOGGER.warning("Could not set off hand item: " + e.getMessage());
                    }
                }
            }
        }

        return true;
    }

    private boolean stripNpcBehavior(Ref<EntityStore> npcRef,
                                     Store<EntityStore> entityStore) {
        try {
            clearInteractionManager(npcRef, entityStore);
            setIdleMovementState(npcRef, entityStore);

            entityStore.removeComponentIfExists(npcRef, NPCEntity.getComponentType());

            return true;
        } catch (Exception e) {
            LOGGER.warning("Could not strip mannequin NPC behavior: " + e.getMessage());
            return false;
        }
    }

    public static boolean removeLoadedMannequinNpc(Ref<EntityStore> npcRef,
                                                   Store<EntityStore> entityStore,
                                                   CommandBuffer<EntityStore> commandBuffer) {
        if (npcRef == null || entityStore == null || commandBuffer == null) return false;

        try {
            ComponentType<EntityStore, NPCEntity> npcComponentType = NPCEntity.getComponentType();
            if (npcComponentType == null) return false;

            NPCEntity npc = entityStore.getComponent(npcRef, npcComponentType);

            if (npc == null
                    || (!isMannequinRole(npc.getRoleName())
                    && !isMannequinRole(npc.getNPCTypeId()))) {
                return false;
            }

            clearInteractionManager(npcRef, entityStore);
            setIdleMovementState(npcRef, entityStore);

            ArmorStandModule.untrackMannequin(npcRef);

            commandBuffer.tryRemoveEntity(npcRef, RemoveReason.REMOVE);

            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void clearInteractionManager(Ref<EntityStore> entityRef,
                                                ComponentAccessor<EntityStore> accessor) {
        try {
            InteractionModule interactionModule = InteractionModule.get();

            if (interactionModule == null || entityRef == null || !entityRef.isValid()) return;

            InteractionManager interactionManager =
                    accessor.getComponent(entityRef, interactionModule.getInteractionManagerComponent());

            if (interactionManager != null) {
                interactionManager.clear();
            }
        } catch (Exception ignored) {}
    }

    private static void setIdleMovementState(Ref<EntityStore> entityRef,
                                             ComponentAccessor<EntityStore> accessor) {
        try {
            if (entityRef == null || !entityRef.isValid()) return;

            MovementStatesComponent movementComponent =
                    accessor.getComponent(entityRef, MovementStatesComponent.getComponentType());

            if (movementComponent == null) return;

            MovementStates states = movementComponent.getMovementStates();

            if (states == null) {
                states = new MovementStates();
                movementComponent.setMovementStates(states);
            }

            if (movementComponent.getSentMovementStates() == null) {
                movementComponent.setSentMovementStates(new MovementStates());
            }

            states.idle = true;
            states.horizontalIdle = true;

            states.jumping = false;
            states.flying = false;
            states.walking = false;
            states.running = false;
            states.sprinting = false;
            states.crouching = false;
            states.forcedCrouching = false;
            states.falling = false;
            states.fallingFar = false;
            states.climbing = false;
            states.inFluid = false;
            states.swimming = false;
            states.swimJumping = false;
            states.onGround = true;
            states.mantling = false;
            states.sliding = false;
            states.mounting = false;
            states.rolling = false;
            states.sitting = false;
            states.gliding = false;
            states.sleeping = false;
        } catch (Exception ignored) {}
    }

    private static String safeWorldName(World world) {
        try {
            return world == null ? "unknown" : world.getName();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private void healToFull(Ref<EntityStore> npcRef) {
        try {
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
        if (rt.mannequinRef != null) {
            Ref<EntityStore> mannequinRef = rt.mannequinRef;

            ArmorStandModule.untrackMannequin(mannequinRef);

            world.execute(() -> {
                try {
                    removeMannequinRef(mannequinRef);
                } catch (Exception ignored) {}
            });

            rt.mannequinRef = null;
            rt.mannequinSpawned = false;
            rt.lastArmorHash = "";
        }
    }

    public static void cleanupBlock(long posKey, World world) {
        BlockRuntime rt = runtimeMap.remove(runtimeKey(world, posKey));

        if (rt != null && rt.mannequinRef != null) {
            Ref<EntityStore> mannequinRef = rt.mannequinRef;

            ArmorStandModule.untrackMannequin(mannequinRef);

            world.execute(() -> {
                try {
                    removeMannequinRef(mannequinRef);
                } catch (Exception ignored) {}
            });
        }
    }

    public static void removeMannequinByUuid(World world, UUID mannequinUuid) {
        if (world == null || mannequinUuid == null) return;

        try {
            Entity entity = world.getEntity(mannequinUuid);

            if (entity != null) {
                Ref<EntityStore> entityRef = entity.getReference();

                if (entityRef != null) {
                    removeMannequinRef(entityRef);
                } else {
                    entity.remove();
                }

                return;
            }
        } catch (Exception ignored) {}

        try {
            Store<EntityStore> entityStore = world.getEntityStore().getStore();

            for (Ref<EntityStore> entityRef : getAllEntityRefs(entityStore)) {
                if (entityRef == null || !entityRef.isValid()) continue;

                UUIDComponent uuidComponent =
                        entityStore.getComponent(entityRef, UUIDComponent.getComponentType());

                if (uuidComponent != null && mannequinUuid.equals(uuidComponent.getUuid())) {
                    removeMannequinRef(entityRef);
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    private static void removeMannequinRef(Ref<EntityStore> mannequinRef) {
        if (mannequinRef == null || !mannequinRef.isValid()) return;

        Store<EntityStore> entityStore = mannequinRef.getStore();
        if (entityStore == null) return;

        ArmorStandModule.untrackMannequin(mannequinRef);

        clearInteractionManager(mannequinRef, entityStore);

        entityStore.removeEntity(mannequinRef, RemoveReason.REMOVE);
    }

    private static void removeDuplicateMannequinsAtBlock(Store<EntityStore> entityStore,
                                                         Ref<EntityStore> keepRef,
                                                         Vector3d blockPos) {
        int removed = 0;

        for (Ref<EntityStore> entityRef : getAllEntityRefs(entityStore)) {
            if (entityRef == null || sameRef(entityRef, keepRef) || !entityRef.isValid()) continue;

            try {
                if (!isMannequinEntity(entityStore, entityRef)) continue;

                TransformComponent transform =
                        entityStore.getComponent(entityRef, TransformComponent.getComponentType());

                if (transform == null || transform.getPosition() == null) continue;

                Vector3d pos = transform.getPosition();

                double dx = pos.x - blockPos.x;
                double dy = pos.y - blockPos.y;
                double dz = pos.z - blockPos.z;

                if ((dx * dx + dy * dy + dz * dz) > 0.09D) continue;

                removeMannequinRef(entityRef);

                removed++;
            } catch (Exception ignored) {}
        }

        if (removed > 0) {
            LOGGER.fine(
                    "Removed "
                            + removed
                            + " duplicate ArmorStand mannequin(s) at block center "
                            + blockPos
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Ref<EntityStore>> getAllEntityRefs(Store<EntityStore> store) {
        List<Ref<EntityStore>> list = new ArrayList<>();

        try {
            Field refsField = store.getClass().getDeclaredField("refs");

            refsField.setAccessible(true);

            Object[] refsArray = (Object[]) refsField.get(store);

            if (refsArray != null) {
                for (Object value : refsArray) {
                    if (value instanceof Ref) {
                        list.add((Ref<EntityStore>) value);
                    }
                }
            }
        } catch (Exception ignored) {}

        return list;
    }

    private static boolean isMannequinEntity(Store<EntityStore> entityStore,
                                             Ref<EntityStore> entityRef) {
        try {
            ComponentType<EntityStore, NPCEntity> npcComponentType = NPCEntity.getComponentType();

            if (npcComponentType != null) {
                NPCEntity npc = entityStore.getComponent(entityRef, npcComponentType);

                if (npc != null
                        && (isMannequinRole(npc.getRoleName())
                        || isMannequinRole(npc.getNPCTypeId()))) {
                    return true;
                }
            }
        } catch (Exception ignored) {}

        try {
            PersistentModel persistentModel =
                    entityStore.getComponent(entityRef, PersistentModel.getComponentType());

            if (persistentModel != null
                    && persistentModel.getModelReference() != null
                    && isMannequinModel(persistentModel.getModelReference().getModelAssetId())) {
                return true;
            }
        } catch (Exception ignored) {}

        try {
            ModelComponent modelComponent =
                    entityStore.getComponent(entityRef, ModelComponent.getComponentType());

            if (modelComponent != null
                    && modelComponent.getModel() != null
                    && isMannequinModel(modelComponent.getModel().getModelAssetId())) {
                return true;
            }
        } catch (Exception ignored) {}

        return false;
    }

    private static boolean isMannequinRole(String roleName) {
        return roleName != null && MANNEQUIN_ROLES.contains(roleName);
    }

    private static boolean isMannequinModel(String modelAssetId) {
        return modelAssetId != null && MANNEQUIN_MODELS.contains(modelAssetId);
    }

    private static boolean sameRef(Ref<EntityStore> left, Ref<EntityStore> right) {
        return left == right
                || (left != null
                && right != null
                && left.getStore() == right.getStore()
                && left.getIndex() == right.getIndex());
    }

    private static boolean isRuntimeMannequinUsable(Ref<EntityStore> ref,
                                                    World world) {
        try {
            if (ref == null || !ref.isValid()) return false;

            Store<EntityStore> store = ref.getStore();
            if (store == null) return false;

            World npcWorld = store.getExternalData().getWorld();

            return safeWorldName(npcWorld).equals(safeWorldName(world));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static BlockRuntimeKey runtimeKey(World world, long posKey) {
        return new BlockRuntimeKey(safeWorldName(world), posKey);
    }

    private static class BlockRuntime {
        Ref<EntityStore> mannequinRef = null;

        boolean mannequinSpawned = false;
        boolean filtersApplied = false;
        boolean wasOpen = false;
        boolean firstTickDone = false;

        String lastArmorHash = "";
        String invalidEquipmentHash = "";

        int healDelayTicks = -1;

        int worldX = 0;
        int worldY = 0;
        int worldZ = 0;

        float yawRadians = 0.0f;
    }

    private record BlockRuntimeKey(String worldName, long posKey) {}
}