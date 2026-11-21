package me.ladypaladra.thearmorymod.armorstand;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The figure an armour stand puts on display. It is an NPC with a mannequin role or model, and
 * this class knows how to recognise one already in the world and take one away. The figure is never
 * durable truth. The block's container is, and the figure is rebuilt from it.
 */
public final class Mannequin {
   public enum LegacyRemoval {
      REMOVED,
      NOT_A_MANNEQUIN,
      FAILED
   }

   private static final HytaleLogger LOGGER = HytaleLogger.get("ArmorStand");
   public static final String ROLE = "ArmorStand_Mannequin";
   public static final String ROLE_STATIC = "ArmorStand_Mannequin_Static";
   public static final String HOLD_ANIMATION = "Hold";
   private static final Set<String> ROLE_NAMES = Set.of(
      "ArmorStand_Mannequin",
      "HubSocial:ArmorStand_Mannequin",
      "ArmorStandMod:ArmorStand_Mannequin",
      "ArmorStand_Mannequin_Static",
      "HubSocial:ArmorStand_Mannequin_Static",
      "ArmorStandMod:ArmorStand_Mannequin_Static"
   );
   private static final Set<String> MODEL_NAMES = Set.of(
      "ArmorStand_Mannequin",
      "HubSocial:ArmorStand_Mannequin",
      "ArmorStandMod:ArmorStand_Mannequin",
      "ArmorStand_Mannequin_Static",
      "HubSocial:ArmorStand_Mannequin_Static",
      "ArmorStandMod:ArmorStand_Mannequin_Static"
   );

   private Mannequin() {
   }

   // The figure is a view of the block's container, never durable truth. Marking it as not serialized keeps it out of the world save.
   // The block's container survives a restart, and the figure is rebuilt from it. Both components go on here because the engine calls
   // this immediately before adding the entity to the store. The figure never exists, even for an instant, either savable or unprotected.
   // Adding protection after the spawn left a window and required a rollback that removed the entity again when protection failed.
   static void prepareHolder(NPCEntity npc, Holder<EntityStore> holder, Store<EntityStore> store) {
      holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());
      holder.addComponent(MannequinShield.getComponentType(), new MannequinShield());
   }

   // Both calls run before either result is tested, on purpose. A figure that can only be half
   // neutralized should still get whichever half works, and short circuiting would skip the second.
   public static boolean neutralize(Ref<EntityStore> figureRef, Store<EntityStore> store) {
      boolean interactionCleared = clearInteractionManager(figureRef, store);
      boolean idleStateSet = setIdleMovementState(figureRef, store);
      if (interactionCleared && idleStateSet) {
         return true;
      }

      String reason;
      if (!interactionCleared && !idleStateSet) {
         reason = "it stayed interactive and its movement state was not set to idle";
      } else if (!interactionCleared) {
         reason = "it stayed interactive";
      } else {
         reason = "its movement state was not set to idle";
      }

      LOGGER.atWarning().log("Could not neutralize the mannequin because " + reason + ", so it is not put on display.");
      return false;
   }

   public static LegacyRemoval removeIfLegacy(Ref<EntityStore> ref, Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer) {
      if (ref != null && store != null && commandBuffer != null) {
         try {
            ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
            if (npcType == null) {
               return LegacyRemoval.FAILED;
            } else {
               NPCEntity npc = store.getComponent(ref, npcType);
               if (npc != null && (isRole(npc.getRoleName()) || isRole(npc.getNPCTypeId()))) {
                  clearInteractionManager(ref, store);
                  setIdleMovementState(ref, store);
                  commandBuffer.tryRemoveEntity(ref, RemoveReason.REMOVE);
                  return LegacyRemoval.REMOVED;
               } else {
                  return LegacyRemoval.NOT_A_MANNEQUIN;
               }
            }
         } catch (Exception e) {
            return LegacyRemoval.FAILED;
         }
      } else {
         return LegacyRemoval.FAILED;
      }
   }

   // Returns false only when something threw. An early return counts as success on purpose: with no
   // interaction module there is no manager to clear, and calling that a failure would abort every
   // spawn on a server where the module never loaded, which is worse than the thing it guards.
   private static boolean clearInteractionManager(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
      try {
         InteractionModule interactionModule = InteractionModule.get();
         if (interactionModule == null || ref == null || !ref.isValid()) {
            return true;
         }

         InteractionManager manager = accessor.getComponent(ref, interactionModule.getInteractionManagerComponent());
         if (manager != null) {
            manager.clear();
         }
         return true;
      } catch (Exception e) {
         return false;
      }
   }

   private static boolean setIdleMovementState(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
      try {
         if (ref == null || !ref.isValid()) {
            return true;
         }

         MovementStatesComponent movement = accessor.getComponent(ref, MovementStatesComponent.getComponentType());
         if (movement == null) {
            return true;
         }

         MovementStates states = movement.getMovementStates();
         if (states == null) {
            states = new MovementStates();
            movement.setMovementStates(states);
         }

         if (movement.getSentMovementStates() == null) {
            movement.setSentMovementStates(new MovementStates());
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
         return true;
      } catch (Exception e) {
         return false;
      }
   }

   public static void remove(Ref<EntityStore> ref) {
      if (ref != null && ref.isValid()) {
         Store<EntityStore> store = ref.getStore();
         if (store != null) {
            clearInteractionManager(ref, store);
            store.removeEntity(ref, RemoveReason.REMOVE);
         }
      }
   }

   /**
    * Collects a reference to every mannequin currently loaded in the entity store.
    *
    * The scan is filtered to entities that carry an NPC component, which is every figure this mod
    * has ever created, instead of walking the whole store.
    *
    * References are collected here and acted on by the caller once this returns. The store holds a
    * processing lock for the duration of the scan and refuses any call that mutates it while that
    * lock is held, so removing an entity from inside the scan throws.
    */
   public static List<Ref<EntityStore>> collectAll(Store<EntityStore> store) {
      List<Ref<EntityStore>> found = new ArrayList<>();
      AtomicInteger probeFailures = new AtomicInteger();
      if (store == null) {
         return found;
      }

      ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
      if (npcType == null) {
         return found;
      }

      try {
         store.forEachChunk(npcType, (chunk, commandBuffer) -> {
            for (int index = 0; index < chunk.size(); index++) {
               Ref<EntityStore> ref = chunk.getReferenceTo(index);
               if (ref != null && ref.isValid() && matches(store, ref, probeFailures)) {
                  found.add(ref);
               }
            }
         });
      } catch (Exception e) {
         LOGGER.atWarning().withCause(e).log("Could not scan the entity store for mannequins");
      }

      int failures = probeFailures.get();
      if (failures > 0) {
         LOGGER.atWarning().log("Could not classify " + failures + " entit(y/ies) while scanning for figures. Some figures may have been missed.");
      }

      return found;
   }

   private static boolean matches(Store<EntityStore> store, Ref<EntityStore> ref, AtomicInteger probeFailures) {
      try {
         ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
         if (npcType != null) {
            NPCEntity npc = store.getComponent(ref, npcType);
            if (npc != null && (isRole(npc.getRoleName()) || isRole(npc.getNPCTypeId()))) {
               return true;
            }
         }
      } catch (Exception e) {
         probeFailures.incrementAndGet();
      }

      try {
         PersistentModel persistentModel = store.getComponent(ref, PersistentModel.getComponentType());
         if (persistentModel != null && persistentModel.getModelReference() != null && isModel(persistentModel.getModelReference().getModelAssetId())) {
            return true;
         }
      } catch (Exception e) {
         probeFailures.incrementAndGet();
      }

      try {
         ModelComponent modelComponent = store.getComponent(ref, ModelComponent.getComponentType());
         if (modelComponent != null && modelComponent.getModel() != null && isModel(modelComponent.getModel().getModelAssetId())) {
            return true;
         }
      } catch (Exception e) {
         probeFailures.incrementAndGet();
      }

      return false;
   }

   private static boolean isRole(String name) {
      return name != null && ROLE_NAMES.contains(name);
   }

   private static boolean isModel(String name) {
      return name != null && MODEL_NAMES.contains(name);
   }
}
