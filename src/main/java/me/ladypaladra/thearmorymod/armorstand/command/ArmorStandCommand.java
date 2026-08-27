package me.ladypaladra.thearmorymod.armorstand.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import me.ladypaladra.thearmorymod.armorstand.ArmorStandModule;
import me.ladypaladra.thearmorymod.armorstand.blockstate.ArmorStandTickSystem;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ArmorStandCommand
 *
 * /armorstand give
 * Gives the player an Armor Stand block.
 *
 * /armorstand cleanup
 * Removes all orphaned ArmorStand_Mannequin NPCs from the world.
 */
public class ArmorStandCommand extends AbstractCommandCollection {

    public static final String ARMOR_STAND_BLOCK_ID = "ArmorStand_Block";

    private static final Set<String> MANNEQUIN_ROLES = Set.of(
            "ArmorStand_Mannequin",
            "HubSocial:ArmorStand_Mannequin",
            "ArmorStandMod:ArmorStand_Mannequin"
    );

    public ArmorStandCommand() {
        super("armorstand", "Armor Stand commands");

        addSubCommand(new GiveCommand());
        addSubCommand(new CleanupCommand());
    }

    private static class GiveCommand extends AbstractPlayerCommand {

        public GiveCommand() {
            super("give", "Get an Armor Stand");
        }

        @Override
        public void execute(CommandContext ctx,
                            Store<EntityStore> store,
                            Ref<EntityStore> ref,
                            PlayerRef playerRef,
                            World world) {
            try {
                ItemStack armorStandItem = new ItemStack(ARMOR_STAND_BLOCK_ID, 1);

                Player player = (Player) store.getComponent(ref, Player.getComponentType());

                if (player != null) {
                    player.giveItem(armorStandItem, ref, store);

                    ctx.sendMessage(Message.join(
                            Message.raw("[Armor Stand] ").color(Color.YELLOW),
                            Message.raw("You received an Armor Stand!").color(Color.GREEN)
                    ));
                } else {
                    ctx.sendMessage(
                            Message.raw("Error: could not get player").color(Color.RED)
                    );
                }
            } catch (Exception e) {
                ctx.sendMessage(
                        Message.raw("Error: " + e.getMessage()).color(Color.RED)
                );
            }
        }
    }

    private static class CleanupCommand extends AbstractPlayerCommand {

        public CleanupCommand() {
            super("cleanup", "Remove all orphaned mannequin NPCs");
        }

        @Override
        public void execute(CommandContext ctx,
                            Store<EntityStore> store,
                            Ref<EntityStore> ref,
                            PlayerRef playerRef,
                            World world) {

            ctx.sendMessage(Message.join(
                    Message.raw("[Armor Stand] ").color(Color.YELLOW),
                    Message.raw("Scanning for orphaned mannequins...").color(Color.GRAY)
            ));

            world.execute(() -> {
                try {
                    Store<EntityStore> entityStore = world.getEntityStore().getStore();

                    List<Ref<EntityStore>> mannequinRefs = new ArrayList<>();
                    ArmorStandTickSystem.forEachEntityRef(entityStore, entityRef -> {
                        if (entityRef == null || !entityRef.isValid()) return false;

                        NPCEntity npc = entityStore.getComponent(
                                entityRef,
                                NPCEntity.getComponentType()
                        );

                        if (npc != null && MANNEQUIN_ROLES.contains(npc.getRoleName())) {
                            mannequinRefs.add(entityRef);
                        }
                        return false;
                    });

                    int removed = 0;
                    for (Ref<EntityStore> mannequinRef : mannequinRefs) {
                        ArmorStandModule.untrackMannequin(mannequinRef);
                        entityStore.removeEntity(mannequinRef, RemoveReason.REMOVE);
                        removed++;
                    }

                    final int count = removed;

                    playerRef.sendMessage(Message.join(
                            Message.raw("[Armor Stand] ").color(Color.YELLOW),
                            Message.raw(
                                    "Removed " + count + " orphaned mannequin(s)."
                            ).color(Color.GREEN)
                    ));
                } catch (Exception e) {
                    playerRef.sendMessage(Message.join(
                            Message.raw("[Armor Stand] ").color(Color.YELLOW),
                            Message.raw(
                                    "Error during cleanup: " + e.getMessage()
                            ).color(Color.RED)
                    ));
                }
            });
        }
    }
}
