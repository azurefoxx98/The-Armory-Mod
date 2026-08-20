package me.ladypaladra.thearmorymod.alteration;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import me.ladypaladra.thearmorymod.alteration.page.AlterationPage;

import javax.annotation.Nonnull;

public final class AlterationModule {

    private AlterationModule() {
    }

    public static void register(@Nonnull JavaPlugin plugin) {
        ComponentType<ChunkStore, AlterationTableBlock> alterationComponentType =
                plugin.getChunkStoreRegistry().registerComponent(
                        AlterationTableBlock.class,
                        "AlterationTableBlock",
                        AlterationTableBlock.CODEC
                );

        AlterationRegistry.setComponentType(alterationComponentType);

        plugin.getCodecRegistry(Interaction.CODEC).register(
                "FeedAlterationTable",
                FeedAlterationTableInteraction.class,
                FeedAlterationTableInteraction.CODEC
        );

        // The Use interaction on every alteration table opens this custom page. The lazy
        // block entity creator attaches a fresh charge component the first time a table
        // placed before this update is opened, so old tables gain the page without a
        // migration pass over the world.
        OpenCustomUIInteraction.registerBlockEntityCustomPage(
                plugin,
                AlterationPage.class,
                "TheArmoryAlterationPage",
                AlterationPage::new,
                () -> {
                    Holder<ChunkStore> holder = ChunkStore.REGISTRY.newHolder();
                    holder.ensureComponent(AlterationTableBlock.getComponentType());
                    return holder;
                }
        );
    }
}
