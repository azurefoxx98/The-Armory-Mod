package me.ladypaladra.thearmorymod.armorstand.command;

import me.ladypaladra.thearmorymod.armorstand.Mannequin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.awt.Color;

public class ArmorStandCommand extends AbstractCommandCollection {
   private static final HytaleLogger LOGGER = HytaleLogger.get("ArmorStand");
   public static final String ARMOR_STAND_BLOCK_ID = "ArmorStand_Block";
   public static final String ARMOR_STAND_STATIC_BLOCK_ID = "ArmorStand_Static_Block";

   public ArmorStandCommand() {
      super("armorstand", "Armor Stand commands");
      this.addSubCommand(new ArmorStandCommand.GiveCommand());
      this.addSubCommand(new ArmorStandCommand.GiveStaticCommand());
      this.addSubCommand(new ArmorStandCommand.CleanupCommand());
   }

   private static void giveBlockItem(CommandContext context, Store<EntityStore> store, Ref<EntityStore> playerRef, String itemId, String itemName) {
      try {
         ItemStack stack = new ItemStack(itemId, 1);
         Player player = store.getComponent(playerRef, Player.getComponentType());
         if (player != null) {
            Player.giveItem(stack, playerRef, store);
            context.sendMessage(
               Message.join(new Message[]{Message.raw("[Armor Stand] ").color(Color.YELLOW), Message.raw("You received " + itemName + "!").color(Color.GREEN)})
            );
         } else {
            context.sendMessage(Message.raw("Error: could not get player").color(Color.RED));
         }
      } catch (Exception e) {
         context.sendMessage(Message.raw("Error: " + e.getMessage()).color(Color.RED));
      }
   }

   private static class CleanupCommand extends AbstractPlayerCommand {
      public CleanupCommand() {
         super("cleanup", "Remove every mannequin, letting the stands rebuild them");
      }

      public void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef, World world) {
         context.sendMessage(
            Message.join(new Message[]{Message.raw("[Armor Stand] ").color(Color.YELLOW), Message.raw("Scanning for mannequins...").color(Color.GRAY)})
         );
         world.execute(
            () -> {
               try {
                  // Declared with its type argument rather than raw. A raw receiver erases the
                  // generics of the whole call, so the collected list would come back as a list of
                  // Object and the loop below would not compile.
                  Store<EntityStore> entityStore = world.getEntityStore().getStore();
                  int removed = 0;

                  for (Ref<EntityStore> figureRef : Mannequin.collectAll(entityStore)) {
                     if (figureRef != null && figureRef.isValid()) {
                        Mannequin.remove(figureRef);
                        removed++;
                     }
                  }

                  // Also written to the server log, not only to whoever typed it. This removes every
                  // figure on the server, and an operator reading the log later has no other way to
                  // know it happened or who did it.
                  // Qualified with the outer class on purpose. The command base class this extends
                  // already inherits a LOGGER of its own, and an inherited member wins over a
                  // lexically enclosing one, so a bare LOGGER here is the engine's and not ours.
                  // Both are the same logger type now, so the compiler can no longer catch that
                  // mistake. A bare LOGGER would compile and write under the engine's name.
                  ArmorStandCommand.LOGGER.atInfo().log(
                     "Cleanup run by " + playerRef.getUsername() + " removed " + removed
                        + " figure(s). Any stand still holding armour builds a new one."
                  );
                  playerRef.sendMessage(
                     Message.join(
                        new Message[]{
                           Message.raw("[Armor Stand] ").color(Color.YELLOW),
                           Message.raw("Removed " + removed + " mannequin(s). Any stand still holding armour builds a new one.").color(Color.GREEN)
                        }
                     )
                  );
               } catch (Exception e) {
                  ArmorStandCommand.LOGGER.atWarning().withCause(e).log(
                     "Cleanup run by " + playerRef.getUsername() + " failed. Some figures may still be standing."
                  );
                  playerRef.sendMessage(
                     Message.join(
                        new Message[]{
                           Message.raw("[Armor Stand] ").color(Color.YELLOW), Message.raw("Error during cleanup: " + e.getMessage()).color(Color.RED)
                        }
                     )
                  );
               }
            }
         );
      }
   }

   private static class GiveCommand extends AbstractPlayerCommand {
      public GiveCommand() {
         super("give", "Get an Armor Stand");
      }

      public void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef, World world) {
         ArmorStandCommand.giveBlockItem(context, store, ref, "ArmorStand_Block", "an Armor Stand");
      }
   }

   private static class GiveStaticCommand extends AbstractPlayerCommand {
      public GiveStaticCommand() {
         super("givestatic", "Get a Static Armor Stand (the Classic look)");
      }

      public void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef, World world) {
         ArmorStandCommand.giveBlockItem(context, store, ref, "ArmorStand_Static_Block", "a Static Armor Stand");
      }
   }
}
