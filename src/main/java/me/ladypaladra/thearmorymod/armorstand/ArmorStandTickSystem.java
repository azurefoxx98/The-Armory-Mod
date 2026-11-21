package me.ladypaladra.thearmorymod.armorstand;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule.BlockStateInfo;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.RoleUtils;
import com.hypixel.hytale.server.npc.util.InventoryHelper;
import it.unimi.dsi.fastutil.Pair;
import java.util.Map;
import org.joml.Vector3d;
import org.joml.Vector3i;

public class ArmorStandTickSystem extends EntityTickingSystem<ChunkStore> {
   private static final HytaleLogger LOGGER = HytaleLogger.get("ArmorStand");
   private final ComponentType<ChunkStore, ArmorStandComponent> componentType;
   private final ComponentType<ChunkStore, BlockStateInfo> blockStateInfoComponentType;
   private final Query<ChunkStore> query;

   public ArmorStandTickSystem(ComponentType<ChunkStore, ArmorStandComponent> componentType) {
      this.componentType = componentType;
      this.blockStateInfoComponentType = BlockStateInfo.getComponentType();
      this.query = Query.and(new Query[]{componentType, this.blockStateInfoComponentType});
   }

   public Query<ChunkStore> getQuery() {
      return this.query;
   }

   public void tick(float dt, int index, ArchetypeChunk<ChunkStore> chunk, Store<ChunkStore> store, CommandBuffer<ChunkStore> commandBuffer) {
      ArmorStandComponent stand = chunk.getComponent(index, this.componentType);
      BlockStateInfo blockInfo = chunk.getComponent(index, this.blockStateInfoComponentType);
      ItemContainerBlock containerBlock = chunk.getComponent(index, ItemContainerBlock.getComponentType());
      if (stand == null || blockInfo == null || containerBlock == null) {
         return;
      }

      SimpleItemContainer container = containerBlock.getItemContainer();
      if (container == null) {
         return;
      }

      Ref<ChunkStore> sectionRef = blockInfo.getSectionRef();
      if (sectionRef == null || !sectionRef.isValid()) {
         return;
      }

      Vector3i position = new Vector3i();
      if (!blockInfo.fillWorldPos(commandBuffer, position)) {
         return;
      }

      int x = position.x;
      int y = position.y;
      int z = position.z;
      float yaw = 0.0F;

      try {
         // The block already knows its own index inside its section, which is exactly what the
         // rotation lookup takes, so there is no need to work local coordinates out from the world
         // position and a fixed section size.
         BlockSection blockSection = commandBuffer.getComponent(sectionRef, BlockSection.getComponentType());
         if (blockSection != null) {
            RotationTuple rotation = blockSection.getRotation(blockInfo.getIndex());
            if (rotation != null && rotation.yaw() != null) {
               yaw = (float)rotation.yaw().getRadians();
            }

            matchPoseToStandType(stand, blockSection.get(blockInfo.getIndex()));
         }
      } catch (Exception e) {
         // Stays silent deliberately. This runs for every stand on every tick, so a line here
         // would flood the log, and the failure is already visible: the figure faces the default
         // direction instead of the way the block is turned.
      }

      World world;
      try {
         world = store.getExternalData().getWorld();
      } catch (Exception e) {
         // Stays silent deliberately, same reason as above. A world that cannot be read is already
         // reporting its own faults, and this path would repeat the news once per stand per tick.
         return;
      }

      if (world == null) {
         return;
      }

      StandRuntime runtime = StandRuntime.at(world, x, y, z);
      runtime.worldX = x;
      runtime.worldY = y;
      runtime.worldZ = z;
      runtime.yawRadians = yaw;
      if (runtime.mannequinRef != null && !StandRuntime.isFigureUsable(runtime.mannequinRef, world)) {
         runtime.mannequinRef = null;
         runtime.mannequinSpawned = false;
         runtime.lastArmorHash = "";
      }

      runtime.watch(container);

      Map<?, ?> windows = containerBlock.getWindows();
      boolean open = windows != null && !windows.isEmpty();
      // A change is acted on only while the stand is closed. While someone has it open there is no
      // figure to update, and closing it rebuilds the figure anyway, so the flag is left to build up
      // until then.
      boolean changedWhileClosed = !open && runtime.contentsChanged;
      if (!open) {
         runtime.contentsChanged = false;
      }

      if (runtime.wasOpen && !open) {
         this.updateMannequin(stand, container, runtime, world);
      }

      if (runtime.healDelayTicks > 0) {
         runtime.healDelayTicks--;
      } else if (runtime.healDelayTicks == 0) {
         runtime.healDelayTicks = -1;
         if (runtime.mannequinRef != null) {
            Ref<EntityStore> figure = runtime.mannequinRef;
            world.execute(() -> this.healToFull(figure));
         }
      }

      if (!runtime.firstTickDone) {
         runtime.firstTickDone = true;
         // Worlds written by earlier versions carry a mannequin id on the block. Nothing writes one
         // now, because the figure is no longer saved, so clear it once and let the value drain out
         // of old saves. The figure it named is removed when it loads, by a path that never needed
         // this value.
         if (stand.getMannequinUuid() != null) {
            stand.setMannequinUuid(null);
         }

         if (!this.buildArmorHash(container).isEmpty()) {
            this.updateMannequin(stand, container, runtime, world);
         }
      } else if (!open && !runtime.mannequinSpawned && !this.buildArmorHash(container).isEmpty()) {
         this.updateMannequin(stand, container, runtime, world);
      } else if (changedWhileClosed) {
         // Something put armour in or took it out without going through the stand's window. The
         // update compares against what the figure already wears, so a change that leaves the
         // armour the same does nothing.
         this.updateMannequin(stand, container, runtime, world);
      }

      // A figure still waiting in the queue counts too, or it would appear while the stand is open.
      if (open && (runtime.mannequinRef != null || runtime.mannequinSpawned)) {
         this.despawnMannequin(runtime, world);
      }

      runtime.wasOpen = open;
   }

   // Whether the figure holds still is part of what kind of stand this is, and the stand's definition
   // says so. Each placed stand also saves its own copy of the value, so a stand placed by an earlier
   // version keeps the pose it was placed with, even after its look changed. The definition wins. This
   // runs before any figure is built, so the figure always comes up in the right pose.
   private static void matchPoseToStandType(ArmorStandComponent stand, int blockId) {
      BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
      if (blockType == null || blockType.getBlockEntity() == null) {
         return;
      }

      ArmorStandComponent definition = blockType.getBlockEntity().getComponent(ArmorStandComponent.getComponentType());
      if (definition != null && definition.isStaticPose() != stand.isStaticPose()) {
         stand.setStaticPose(definition.isStaticPose());
      }
   }

   // A weapon in the figure's hand brings its own stance, which the game client plays on the whole
   // body: arms raised, one leg forward, the armour pushed off the stand. A still clip in the model's
   // idle slot does not stop it, because the client picks that slot's animation from the held item.
   // So the figure plays a pose that pins every bone at rest in the server action slot, the highest
   // one. The engine also records it, so a player who walks up later is shown the same pose.
   private static void holdStill(NPCEntity figure, Ref<EntityStore> figureRef, Store<EntityStore> entityStore) {
      try {
         figure.playAnimation(figureRef, AnimationSlot.ServerAction, Mannequin.HOLD_ANIMATION, true, entityStore);
      } catch (Exception e) {
         LOGGER.atWarning().withCause(e).log("Could not hold the figure still, it may take its weapon's stance");
      }
   }

   private String buildArmorHash(SimpleItemContainer container) {
      StringBuilder hash = new StringBuilder();

      for (short slot = 0; slot < Math.min(container.getCapacity(), 6); slot++) {
         ItemStack stack = container.getItemStack(slot);
         if (stack != null && !stack.isEmpty()) {
            hash.append(slot).append("=").append(stack.getItem().getId()).append(";");
         }
      }

      return hash.toString();
   }

   private void updateMannequin(ArmorStandComponent stand, SimpleItemContainer container, StandRuntime runtime, World world) {
      try {
         String hash = this.buildArmorHash(container);
         if (hash.equals(runtime.invalidEquipmentHash)) {
            return;
         }

         if (hash.equals(runtime.lastArmorHash) && runtime.mannequinSpawned) {
            return;
         }

         this.despawnMannequin(runtime, world);
         if (hash.isEmpty()) {
            runtime.lastArmorHash = hash;
            runtime.invalidEquipmentHash = "";
            return;
         }

         runtime.mannequinSpawned = true;
         runtime.lastArmorHash = hash;
         int generation = ++runtime.spawnGeneration;
         world.execute(() -> this.spawnMannequinSync(stand, container, runtime, world, hash, generation));
         runtime.healDelayTicks = 10;
      } catch (Exception e) {
         LOGGER.atWarning().withCause(e).log("Error updating mannequin");
      }
   }

   private void spawnMannequinSync(ArmorStandComponent stand, SimpleItemContainer container, StandRuntime runtime, World world, String hash, int generation) {
      try {
         // This runs on the world queue, so the block can have been broken or unloaded since it was
         // scheduled. Its runtime entry is already out of the map by then, so a figure created here
         // would be attached to nothing and could never be found again.
         if (runtime.removed) {
            return;
         }

         // Something newer happened while this waited in the queue: the stand was opened, or its
         // contents changed and a later spawn is already scheduled. That later state wins. Without
         // this the stale figure would appear for a tick and be taken away again, or stand next to
         // the newer one until the duplicate sweep removed it.
         if (generation != runtime.spawnGeneration) {
            return;
         }

         Vector3d spawnPosition = new Vector3d(runtime.worldX + 0.5, runtime.worldY, runtime.worldZ + 0.5);
         float facing = runtime.yawRadians + (float) Math.PI;
         String role = stand.isStaticPose() ? Mannequin.ROLE_STATIC : Mannequin.ROLE;
         NPCPlugin npcPlugin = NPCPlugin.get();
         if (npcPlugin != null && !npcPlugin.hasRoleName(role) && !role.equals(Mannequin.ROLE)) {
            LOGGER.atWarning().log("Role " + role + " is not registered, falling back to " + Mannequin.ROLE + " so the stand is not left empty");
            role = Mannequin.ROLE;
         }

         if (npcPlugin == null || !npcPlugin.hasRoleName(role)) {
            runtime.mannequinSpawned = false;
            runtime.lastArmorHash = "";
            LOGGER.atWarning().log("ArmorStand mannequin role is not registered: " + role + " (loadout hash " + hash + ")");
            return;
         }

         if (MannequinShield.getComponentType() == null) {
            runtime.mannequinSpawned = false;
            runtime.lastArmorHash = "";
            LOGGER.atWarning().log("ArmorStand mannequin not spawned: the protection component is not registered yet");
            return;
         }

         Rotation3f rotation = new Rotation3f(0.0F, facing, 0.0F);
         Store<EntityStore> entityStore = world.getEntityStore().getStore();
         Pair<Ref<EntityStore>, NPCEntity> spawned =
            npcPlugin.spawnEntity(entityStore, npcPlugin.getIndex(role), spawnPosition, rotation, null, Mannequin::prepareHolder, null);
         Ref<EntityStore> figureRef = spawned != null ? (Ref<EntityStore>)spawned.first() : null;
         NPCEntity figure = figureRef != null && figureRef.isValid() ? (NPCEntity)spawned.second() : null;
         if (figure != null) {
            if (!Mannequin.neutralize(figureRef, entityStore)) {
               // Whether the figure actually went away decides what this can honestly claim. Saying
               // it was withdrawn when the removal threw would report a clean recovery while a stray
               // figure is still standing at the block.
               boolean withdrawn = true;

               try {
                  figure.remove();
               } catch (Exception e) {
                  withdrawn = false;
               }

               runtime.mannequinSpawned = false;
               runtime.lastArmorHash = "";
               runtime.invalidEquipmentHash = hash;
               LOGGER.atWarning().log(
                  "ArmorStand mannequin not displayed because NPC behavior could not be neutralized for loadout hash "
                     + hash + " (role=" + role + ")"
                     + (withdrawn ? "" : ", and removing it failed so it may still be standing")
               );
               return;
            }

            boolean equipped = this.equipFromContainer(figure, container);
            if (!equipped) {
               boolean withdrawn = true;

               try {
                  Mannequin.remove(figureRef);
               } catch (Exception e) {
                  withdrawn = false;
               }

               runtime.mannequinSpawned = false;
               runtime.lastArmorHash = "";
               runtime.invalidEquipmentHash = hash;
               LOGGER.atWarning().log(
                  "ArmorStand mannequin not displayed because equipment apply failed for loadout hash "
                     + hash + " (role=" + role + ")"
                     + (withdrawn ? "" : ", and removing it failed so it may still be standing")
               );
               return;
            }

            removeDuplicateMannequinsAtBlock(entityStore, figureRef, spawnPosition);
            markEquipmentOutdated(figureRef, entityStore);
            if (stand.isStaticPose()) {
               holdStill(figure, figureRef, entityStore);
            }
            runtime.invalidEquipmentHash = "";
            runtime.mannequinRef = figureRef;
         } else {
            runtime.mannequinSpawned = false;
            runtime.lastArmorHash = "";
            LOGGER.atWarning().log("ArmorStand mannequin spawn returned null for role " + role + " (loadout hash " + hash + ")");
         }
      } catch (Exception e) {
         runtime.mannequinSpawned = false;
         runtime.lastArmorHash = "";
         LOGGER.atWarning().withCause(e).log("Failed to spawn mannequin (loadout hash " + hash + ")");
      }
   }

   private boolean equipFromContainer(NPCEntity figure, SimpleItemContainer container) {
      Ref<EntityStore> figureRef = figure.getReference();
      if (figureRef != null && figureRef.isValid()) {
         Store<EntityStore> entityStore = figureRef.getStore();
         if (entityStore == null) {
            return false;
         } else {
            for (short slot = 0; slot < Math.min(container.getCapacity(), 4); slot++) {
               ItemStack armor = container.getItemStack(slot);
               if (armor != null && !armor.isEmpty()) {
                  try {
                     if (armor.getItem() == null || armor.getItem().getId() == null || armor.getItem().getId().isEmpty()) {
                        return false;
                     }

                     RoleUtils.setArmor(figureRef, figure, armor.getItem().getId(), entityStore);
                  } catch (Exception e) {
                     LOGGER.atWarning().withCause(e).log("Could not set armor slot " + slot);
                     return false;
                  }
               }
            }

            if (container.getCapacity() > 4) {
               ItemStack mainHand = container.getItemStack((short)4);
               if (mainHand != null && !mainHand.isEmpty()) {
                  try {
                     if (mainHand.getItem() != null && mainHand.getItem().getId() != null && !mainHand.getItem().getId().isEmpty()) {
                        RoleUtils.setItemInHand(figureRef, figure, mainHand.getItem().getId(), entityStore);
                     } else {
                        LOGGER.atWarning().log("Could not set main hand item: missing item id");
                     }
                  } catch (Exception e) {
                     LOGGER.atWarning().withCause(e).log("Could not set main hand item");
                  }
               }
            }

            if (container.getCapacity() > 5) {
               ItemStack offHand = container.getItemStack((short)5);
               if (offHand != null && !offHand.isEmpty()) {
                  try {
                     if (offHand.getItem() != null && offHand.getItem().getId() != null && !offHand.getItem().getId().isEmpty()) {
                        InventoryHelper.setOffHandItem(figureRef, offHand.getItem().getId(), (byte)0, entityStore);
                     } else {
                        LOGGER.atWarning().log("Could not set off hand item: missing item id");
                     }
                  } catch (Exception e) {
                     LOGGER.atWarning().withCause(e).log("Could not set off hand item");
                  }
               }
            }

            return true;
         }
      } else {
         return false;
      }
   }

   private static void markEquipmentOutdated(Ref<EntityStore> figureRef, Store<EntityStore> entityStore) {
      try {
         if (figureRef == null || !figureRef.isValid()) {
            return;
         }

         InventoryComponent.Armor armor = entityStore.getComponent(figureRef, InventoryComponent.Armor.getComponentType());
         if (armor != null) {
            armor.setOutdatedEquipment(true);
         }

         InventoryComponent.Hotbar hotbar = entityStore.getComponent(figureRef, InventoryComponent.Hotbar.getComponentType());
         if (hotbar != null) {
            hotbar.setOutdatedEquipment(true);
         }

         InventoryComponent.Utility utility = entityStore.getComponent(figureRef, InventoryComponent.Utility.getComponentType());
         if (utility != null) {
            utility.setOutdatedEquipment(true);
         }
      } catch (Exception e) {
         LOGGER.atWarning().withCause(e).log("Could not mark mannequin equipment as outdated");
      }
   }

   private void healToFull(Ref<EntityStore> figureRef) {
      try {
         if (figureRef == null || !figureRef.isValid()) {
            return;
         }

         Store<EntityStore> entityStore = figureRef.getStore();
         EntityStatMap stats = entityStore.getComponent(figureRef, EntityStatMap.getComponentType());
         if (stats == null) {
            return;
         }

         EntityStatValue health = stats.get(DefaultEntityStatTypes.getHealth());
         if (health != null) {
            stats.setStatValue(DefaultEntityStatTypes.getHealth(), health.getMax());
         }
      } catch (Exception e) {
         LOGGER.atWarning().withCause(e).log("Failed to heal mannequin");
      }
   }

   private void despawnMannequin(StandRuntime runtime, World world) {
      // Cancels a spawn still waiting in the queue as well as removing a figure already standing.
      // Opening the stand straight after closing it used to let the pending figure appear anyway.
      runtime.spawnGeneration++;
      if (runtime.mannequinRef != null) {
         Ref<EntityStore> figureRef = runtime.mannequinRef;
         int x = runtime.worldX;
         int y = runtime.worldY;
         int z = runtime.worldZ;
         world.execute(() -> {
            try {
               Mannequin.remove(figureRef);
            } catch (Exception e) {
               LOGGER.atWarning().withCause(e).log(
                  "Could not remove the figure for the armour stand at " + x + "," + y + "," + z
                     + ". It may stay until its chunk unloads."
               );
            }
         });
         runtime.mannequinRef = null;
      }

      // Reset even when no figure was standing yet. A cancelled spawn would otherwise leave the
      // stand believing its figure is on the way, and the next update would see the same armour,
      // decide nothing changed, and never build one.
      runtime.mannequinSpawned = false;
      runtime.lastArmorHash = "";
   }

   private static void removeDuplicateMannequinsAtBlock(Store<EntityStore> entityStore, Ref<EntityStore> keep, Vector3d blockCentre) {
      int removed = 0;
      int failures = 0;

      // The scan has finished and released the store's processing lock by the time this loop runs,
      // which is what makes removing from here legal.
      for (Ref<EntityStore> other : Mannequin.collectAll(entityStore)) {
         if (other == null || !other.isValid() || sameRef(other, keep)) {
            continue;
         }

         try {
            TransformComponent transform = entityStore.getComponent(other, TransformComponent.getComponentType());
            if (transform != null && transform.getPosition() != null) {
               Vector3d otherPosition = transform.getPosition();
               double dx = otherPosition.x - blockCentre.x;
               double dy = otherPosition.y - blockCentre.y;
               double dz = otherPosition.z - blockCentre.z;
               if (!(dx * dx + dy * dy + dz * dz > 0.09)) {
                  Mannequin.remove(other);
                  removed++;
               }
            }
         } catch (Exception e) {
            failures++;
         }
      }

      // The position handed in is the block's centre, so it carries a half on x and z. Report whole
      // block coordinates instead, which is the form every other position in this mod's log uses and
      // the one someone can actually go and stand on.
      int blockX = (int)Math.floor(blockCentre.x);
      int blockY = (int)Math.floor(blockCentre.y);
      int blockZ = (int)Math.floor(blockCentre.z);

      if (removed > 0) {
         LOGGER.atInfo().log(
            "Removed " + removed + " duplicate figure(s) at the armour stand at " + blockX + "," + blockY + "," + blockZ
               + ". Duplicates are not expected on this build and are worth reporting."
         );
      }

      if (failures > 0) {
         LOGGER.atWarning().log(
            "Could not examine " + failures + " entit(y/ies) while checking for duplicate figures at "
               + blockX + "," + blockY + "," + blockZ + "."
         );
      }
   }

   // Whether two references point at the same entity. Lives here rather than with the figure
   // because it says nothing about mannequins, and the duplicate sweep above is its only caller.
   private static boolean sameRef(Ref<EntityStore> first, Ref<EntityStore> second) {
      return first == second || first != null && second != null && first.getStore() == second.getStore() && first.getIndex() == second.getIndex();
   }

}
