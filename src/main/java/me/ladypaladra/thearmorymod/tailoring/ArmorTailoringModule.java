package me.ladypaladra.thearmorymod.tailoring;

import com.hypixel.hytale.server.core.plugin.registry.AssetRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import me.ladypaladra.thearmorymod.tailoring.assets.ArmorVariantStore;
import me.ladypaladra.thearmorymod.tailoring.component.ArmorTailoringBenchBlock;
import me.ladypaladra.thearmorymod.tailoring.interaction.OpenArmorTailoringBenchInteraction;

import javax.annotation.Nonnull;

public final class ArmorTailoringModule {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static ComponentType<ChunkStore, ArmorTailoringBenchBlock> armorTailoringBenchBlockComponentType;

    private ArmorTailoringModule() {
    }

    public static void register(@Nonnull JavaPlugin plugin) {
        AssetRegistry assetRegistry = plugin.getAssetRegistry();

        assetRegistry.register(ArmorVariantStore.create());

        armorTailoringBenchBlockComponentType = plugin.getChunkStoreRegistry().registerComponent(
                ArmorTailoringBenchBlock.class,
                "ArmorTailoringBenchBlock",
                ArmorTailoringBenchBlock.CODEC
        );

        plugin.getCodecRegistry(Interaction.CODEC).register(
                "OpenArmorTailoringBench",
                OpenArmorTailoringBenchInteraction.class,
                OpenArmorTailoringBenchInteraction.CODEC
        );

        LOGGER.atInfo().log("Armor tailoring module registered.");
    }

    @Nonnull
    public static ComponentType<ChunkStore, ArmorTailoringBenchBlock> getArmorTailoringBenchBlockComponentType() {
        if (armorTailoringBenchBlockComponentType == null) {
            throw new IllegalStateException("ArmorTailoringModule has not been registered yet.");
        }

        return armorTailoringBenchBlockComponentType;
    }
}