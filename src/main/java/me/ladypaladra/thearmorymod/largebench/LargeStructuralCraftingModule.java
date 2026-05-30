package me.ladypaladra.thearmorymod.largebench;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import me.ladypaladra.thearmorymod.largebench.interaction.OpenLargeStructuralCraftingBenchInteraction;

import javax.annotation.Nonnull;

public final class LargeStructuralCraftingModule {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private LargeStructuralCraftingModule() {
    }

    public static void register(@Nonnull JavaPlugin plugin) {
        plugin.getCodecRegistry(Interaction.CODEC).register(
                "OpenLargeStructuralCraftingBench",
                OpenLargeStructuralCraftingBenchInteraction.class,
                OpenLargeStructuralCraftingBenchInteraction.CODEC
        );

        LOGGER.atInfo().log("Large structural crafting module registered.");
    }
}