package me.ladypaladra.thearmorymod.armorstand;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import me.ladypaladra.thearmorymod.armorstand.command.ArmorStandCommand;

import javax.annotation.Nonnull;

/**
 * Armor stands: a block whose container holds the armour, and a figure that wears copies of it
 * while the container is closed.
 *
 * <p>Every id registered here is the one the standalone Armor Stand mod used, so a world that held
 * its stands keeps them when a server moves to The Armory. That also means the two cannot run on
 * one server, because each registered id can only belong to one plugin.</p>
 */
public final class ArmorStandModule {

    private ArmorStandModule() {
    }

    public static void register(@Nonnull JavaPlugin plugin) {
        ComponentType<ChunkStore, ArmorStandComponent> standType =
                plugin.getChunkStoreRegistry().registerComponent(
                        ArmorStandComponent.class,
                        "ArmorStand_Block",
                        ArmorStandComponent.CODEC
                );
        ArmorStandComponent.setComponentType(standType);

        ArmorStandLegacyMannequinSystem legacySystem = new ArmorStandLegacyMannequinSystem();
        plugin.getEntityStoreRegistry().registerSystem(legacySystem);
        if (!legacySystem.isNarrowed()) {
            plugin.getLogger().atWarning().log(
                    "Armor stands: the NPC component type was not available at setup, so the watch for figures "
                            + "saved by older builds runs on every entity. Slower on world load but still correct.");
        }

        // The id is the one Spanish name kept untranslated. It is the key an older build saved this
        // component under, and the engine keeps an id it no longer knows in an Unknown bag rather than
        // dropping it, so renaming it would strand that data under a key nothing reads.
        MannequinShield.setComponentType(plugin.getEntityStoreRegistry().registerComponent(
                MannequinShield.class,
                "ArmorStand_Maniqui",
                MannequinShield.CODEC
        ));
        plugin.getEntityStoreRegistry().registerSystem(new MannequinShieldSystem());

        plugin.getChunkStoreRegistry().registerSystem(new ArmorStandTickSystem(standType));
        plugin.getChunkStoreRegistry().registerSystem(new ArmorStandOnRemoveSystem(standType));
        plugin.getCommandRegistry().registerCommand(new ArmorStandCommand());
    }
}
