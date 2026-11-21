package me.ladypaladra.thearmorymod.armorstand;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;

public final class ArmorStandLegacyMannequinSystem extends RefSystem<EntityStore> {
   private static final HytaleLogger LOGGER = HytaleLogger.get("ArmorStand");
   private final Query<EntityStore> query;
   private final boolean narrowed;
   private final AtomicLong removedTotal = new AtomicLong();
   private final AtomicLong failedTotal = new AtomicLong();

   public ArmorStandLegacyMannequinSystem() {
      // Every figure this watches for is an NPC, so it only needs to see entities carrying that
      // component. Without the narrowing the callback runs for every entity in the world as it
      // loads, which is a cost paid on every load forever for a migration that finishes once.
      //
      // The engine marks this lookup as possibly null, and whether it is populated this early
      // depends on load order, so fall back to watching everything rather than failing. The
      // fallback is correct, just slower, and the plugin reports which one is in use.
      ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
      this.narrowed = npcType != null;
      this.query = this.narrowed ? Query.and(npcType) : Query.any();
   }

   /**
    * Whether the watch is limited to NPC entities, rather than every entity in the world.
    */
   public boolean isNarrowed() {
      return this.narrowed;
   }

   public void onEntityAdded(
      @Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer
   ) {
      if (reason == AddReason.LOAD) {
         Mannequin.LegacyRemoval result = Mannequin.removeIfLegacy(ref, store, commandBuffer);
         switch (result) {
            case REMOVED:
               long removed = this.removedTotal.incrementAndGet();
               if (removed == 1L) {
                  LOGGER.atInfo().log(
                     "Removed an armour stand figure saved by an older build. Figures are no longer written to the "
                        + "world save, they are rebuilt from the stand's container, so this runs once per old figure "
                        + "as its chunk loads."
                  );
               } else if (isMilestone(removed)) {
                  LOGGER.atInfo().log("Removed " + removed + " armour stand figures saved by older builds so far.");
               }
               break;
            case FAILED:
               long failed = this.failedTotal.incrementAndGet();
               if (failed == 1L) {
                  LOGGER.atWarning().log(
                     "Could not check a loading entity for a legacy armour stand figure. An old figure may still be "
                        + "left in the world save."
                  );
               } else if (isMilestone(failed)) {
                  LOGGER.atWarning().log("Could not check a loading entity for a legacy armour stand figure, " + failed + " times so far.");
               }
               break;
            case NOT_A_MANNEQUIN:
               break;
         }
      }
   }

   /**
    * Whether a running total is one worth writing to the log.
    *
    * This fires for every entity that loads, so a line each would flood a server migrating a large
    * world. Reporting the first and then every power of ten keeps it under about ten lines however
    * many figures there are, while still showing an operator that the migration is running.
    */
   private static boolean isMilestone(long total) {
      if (total == 1L) {
         return true;
      }
      for (long step = 10L; step <= 1000000000L; step *= 10L) {
         if (total == step) {
            return true;
         }
      }
      return false;
   }

   public void onEntityRemove(
      @Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer
   ) {
   }

   @Nonnull
   public Query<EntityStore> getQuery() {
      return this.query;
   }
}
