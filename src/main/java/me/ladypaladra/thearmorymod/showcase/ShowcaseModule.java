package me.ladypaladra.thearmorymod.showcase;

import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * The showcase world's figures, and the guard that keeps its blocks out of players' hands.
 *
 * <p>Every id registered here is The Armory's own. The Armor Stand mod has a stand of the same
 * look, and a server may run both, so no component, role, model or block is shared with it.</p>
 */
public final class ShowcaseModule {

    private ShowcaseModule() {
    }

    public static void register(@Nonnull JavaPlugin plugin) {
        ComponentRegistryProxy<ChunkStore> chunks = plugin.getChunkStoreRegistry();
        ComponentRegistryProxy<EntityStore> entities = plugin.getEntityStoreRegistry();

        // Component types first: the systems below read them when they are built.
        ShowcaseFigureStand.setComponentType(
                chunks.registerComponent(ShowcaseFigureStand.class, "ArmoryShowcaseFigureStand", ShowcaseFigureStand.CODEC));
        ShowcaseFigure.Shield.setComponentType(
                entities.registerComponent(ShowcaseFigure.Shield.class, "ArmoryShowcaseFigureShield", ShowcaseFigure.Shield.CODEC));

        chunks.registerSystem(new ShowcaseFigureSystems.Tick());
        chunks.registerSystem(new ShowcaseFigureSystems.Remove());
        entities.registerSystem(new ShowcaseFigureSystems.Shield());
        entities.registerSystem(new HiddenItemGuard());
        ShowcasePlatform.register(entities);
    }
}
