package me.ladypaladra.thearmorymod.armorstand;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.ArmorSlotAddFilter;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.inventory.container.filter.NoDuplicateFilter;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An instance is the mod's working state for one armour stand, and the class itself holds the
 * registry of those instances, keyed by world name and block position. None of it is saved. It is
 * rebuilt as stands tick, and an entry outliving its block is the bug the detach path exists to
 * prevent.
 */
public final class StandRuntime {
   private static final HytaleLogger LOGGER = HytaleLogger.get("ArmorStand");
   private static final Map<Key, StandRuntime> RUNTIMES = new ConcurrentHashMap<>();

   Ref<EntityStore> mannequinRef = null;
   boolean mannequinSpawned = false;
   // Raised by the container's own change event, from whichever thread made the change, and read
   // by the tick on the world thread. The listener does nothing else, because the engine dispatches
   // that event without holding any lock.
   volatile boolean contentsChanged = false;
   // Counts every spawn scheduled and every removal. A spawn runs one queue step after it was
   // scheduled, so it compares the number it was given with this one and gives up when anything
   // newer happened in between, such as the window opening or the contents changing again.
   int spawnGeneration = 0;
   private SimpleItemContainer watchedContainer = null;
   private EventRegistration<?, ?> changeRegistration = null;
   boolean wasOpen = false;
   boolean firstTickDone = false;
   String lastArmorHash = "";
   String invalidEquipmentHash = "";
   int healDelayTicks = -1;
   int worldX = 0;
   int worldY = 0;
   int worldZ = 0;
   float yawRadians = 0.0F;
   // Set when the block goes away, so a spawn scheduled before that cannot create a figure
   // afterwards. It is volatile because the map is concurrent, even though block removal and
   // the deferred spawn both currently run on the world thread.
   volatile boolean removed = false;

   private StandRuntime() {
   }

   /**
    * The working state for the stand at this position, creating it if this is the first tick.
    */
   public static StandRuntime at(World world, int x, int y, int z) {
      return RUNTIMES.computeIfAbsent(key(world, x, y, z), ignored -> new StandRuntime());
   }

   /**
    * Makes sure this stand is listening to the container it has now, with its slot filters on.
    *
    * The engine can replace a block's container with a resized copy, so the identity is checked on
    * every tick rather than assumed. A replacement gets the filters and the listener again, and
    * counts as a change, because the figure has to be checked against what the new one holds.
    */
   void watch(SimpleItemContainer container) {
      if (container == this.watchedContainer) {
         return;
      }

      this.unwatch();
      applySlotFilters(container);
      try {
         this.changeRegistration = container.registerChangeEvent(event -> this.contentsChanged = true);
      } catch (Exception e) {
         // Not fatal. Closing the stand's window still updates the figure, which is how it worked
         // before the listener existed. Only changes made some other way go unseen.
         LOGGER.atWarning().withCause(e).log(
            "Could not listen for changes to the armour stand at " + this.worldX + "," + this.worldY + "," + this.worldZ
               + ". Its figure still updates when the stand is closed."
         );
      }

      this.watchedContainer = container;
      this.contentsChanged = true;
   }

   private void unwatch() {
      if (this.changeRegistration != null) {
         try {
            this.changeRegistration.unregister();
         } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log(
               "Could not stop listening to the armour stand at " + this.worldX + "," + this.worldY + "," + this.worldZ + "."
            );
         }
      }

      this.changeRegistration = null;
      this.watchedContainer = null;
   }

   public static void applySlotFilters(SimpleItemContainer container) {
      if (container != null) {
         try {
            short capacity = container.getCapacity();
            if (capacity > 0) {
               container.setSlotFilter(FilterActionType.ADD, (short)0, new ArmorSlotAddFilter(ItemArmorSlot.Head));
            }

            if (capacity > 1) {
               container.setSlotFilter(FilterActionType.ADD, (short)1, new ArmorSlotAddFilter(ItemArmorSlot.Chest));
            }

            if (capacity > 2) {
               container.setSlotFilter(FilterActionType.ADD, (short)2, new ArmorSlotAddFilter(ItemArmorSlot.Hands));
            }

            if (capacity > 3) {
               container.setSlotFilter(FilterActionType.ADD, (short)3, new ArmorSlotAddFilter(ItemArmorSlot.Legs));
            }

            if (capacity > 4) {
               container.setSlotFilter(FilterActionType.ADD, (short)4, new NoDuplicateFilter(container));
            }

            if (capacity > 5) {
               // The off hand slot takes only what the engine lets an off hand hold, the same test
               // the figure's own utility inventory applies. A sword put here used to be accepted
               // by the stand and then refused by the figure, so it sat in the stand and never showed.
               NoDuplicateFilter noDuplicate = new NoDuplicateFilter(container);
               container.setSlotFilter(FilterActionType.ADD, (short)5, (action, target, slot, stack) ->
                  noDuplicate.test(action, target, slot, stack)
                     && (stack == null || stack.getItem().getUtility().isUsable()));
            }
         } catch (Exception e) {
            LOGGER.atWarning().withCause(e).log("Could not apply armor slot filters");
         }
      }
   }

   /**
    * Drops the runtime entry for a block that has been broken, and removes its figure.
    */
   public static void cleanupBlock(int x, int y, int z, World world) {
      detach(x, y, z, world, true);
   }

   /**
    * Drops the runtime entry for a block whose chunk is unloading, without touching the figure.
    *
    * The figure is not saved, so the engine takes it away with the chunk and there is nothing to
    * despawn. Dropping the entry keeps the map from growing for the life of the process, and means
    * the block starts from a clean entry if the chunk comes back.
    */
   public static void forgetBlock(int x, int y, int z, World world) {
      detach(x, y, z, world, false);
   }

   private static void detach(int x, int y, int z, World world, boolean despawnFigure) {
      StandRuntime runtime = RUNTIMES.remove(key(world, x, y, z));
      if (runtime == null) {
         return;
      }

      // Set before anything else. A spawn scheduled on the world queue before the block went away
      // has not run yet, and without this it would create a figure and attach it to an entry that
      // is no longer in the map, leaving something nothing can find or remove.
      runtime.removed = true;
      // The listener holds this entry, so it has to go with it. Left registered, a container that
      // outlives the block would keep raising a flag nothing reads.
      runtime.unwatch();

      if (despawnFigure && runtime.mannequinRef != null) {
         Ref<EntityStore> figureRef = runtime.mannequinRef;
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
      }
   }

   public static boolean isFigureUsable(Ref<EntityStore> figureRef, World world) {
      try {
         if (figureRef != null && figureRef.isValid()) {
            Store<EntityStore> store = figureRef.getStore();
            if (store == null) {
               return false;
            } else {
               World figureWorld = store.getExternalData().getWorld();
               return safeWorldName(figureWorld).equals(safeWorldName(world));
            }
         } else {
            return false;
         }
      } catch (Throwable e) {
         return false;
      }
   }

   private static Key key(World world, int x, int y, int z) {
      return new Key(safeWorldName(world), x, y, z);
   }

   private static String safeWorldName(World world) {
      try {
         return world == null ? "unknown" : world.getName();
      } catch (Throwable e) {
         return "unknown";
      }
   }

   // Keyed by the three coordinates rather than a packed long. The engine's packing puts Y in a
   // signed 9 bit field that cannot represent the world's full coordinate space, and this is a map
   // key, so two positions sharing a key would share one figure reference.
   private record Key(String worldName, int x, int y, int z) {
   }
}
