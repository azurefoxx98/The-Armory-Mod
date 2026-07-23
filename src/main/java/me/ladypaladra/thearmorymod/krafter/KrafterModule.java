package me.ladypaladra.thearmorymod.krafter;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;

public final class KrafterModule {

    private KrafterModule() {
    }

    public static void register(@Nonnull JavaPlugin plugin) {
        plugin.getCodecRegistry(Interaction.CODEC).register(
                "MekanichalKrafterInteraction",
                MekanichalKrafterInteraction.class,
                MekanichalKrafterInteraction.CODEC
        );

        ComponentType<ChunkStore, MekanichalKrafterBlock> krafterComponentType =
                plugin.getChunkStoreRegistry().registerComponent(
                        MekanichalKrafterBlock.class,
                        "MekanichalKrafterBlock",
                        MekanichalKrafterBlock.CODEC
                );

        MekanichalKrafterRegistry.setComponentType(krafterComponentType);

        plugin.getChunkStoreRegistry().registerSystem(
                new MekanichalKrafterTickSystem(krafterComponentType)
        );
    }
}