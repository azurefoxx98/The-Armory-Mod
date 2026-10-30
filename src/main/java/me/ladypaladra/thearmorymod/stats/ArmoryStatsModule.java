package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.stats.systems.ArrowDamageBonusSystem;
import me.ladypaladra.thearmorymod.stats.systems.GroundMoveSpeedModifierSystem;
import me.ladypaladra.thearmorymod.stats.systems.JumpHeightModifierSystem;
import me.ladypaladra.thearmorymod.stats.systems.SwimSpeedModifierSystem;

public final class ArmoryStatsModule {

    private static final ArmoryStatService STAT_SERVICE = new ArmoryStatService();

    private static final MultiplicativeTooltipFilter TOOLTIP_FILTER = new MultiplicativeTooltipFilter();

    private ArmoryStatsModule() {
    }

    public static void register(JavaPlugin plugin) {
        plugin.getEntityStoreRegistry().registerSystem(new ArrowDamageBonusSystem(STAT_SERVICE));

        ComponentType<EntityStore, GroundSpeedState> groundSpeedType =
                plugin.getEntityStoreRegistry().registerComponent(GroundSpeedState.class, GroundSpeedState::new);
        plugin.getEntityStoreRegistry().registerSystem(new GroundMoveSpeedModifierSystem(groundSpeedType));
        ComponentType<EntityStore, JumpForceState> jumpForceType =
                plugin.getEntityStoreRegistry().registerComponent(JumpForceState.class, JumpForceState::new);
        plugin.getEntityStoreRegistry().registerSystem(new JumpHeightModifierSystem(jumpForceType));

        ComponentType<EntityStore, SwimBoostState> swimBoostType =
                plugin.getEntityStoreRegistry().registerComponent(SwimBoostState.class, SwimBoostState::new);
        plugin.getEntityStoreRegistry().registerSystem(new SwimSpeedModifierSystem(swimBoostType));

        PacketAdapters.registerOutbound(TOOLTIP_FILTER);
    }

    /**
     * The packet filter list is static and outlives the plugin, so a reloaded plugin would otherwise
     * correct every tooltip twice.
     */
    public static void unregister() {
        try {
            PacketAdapters.deregisterOutbound(TOOLTIP_FILTER);
        } catch (IllegalArgumentException notRegistered) {
            // setup failed before the filter was added, so there is nothing to take out
        }
    }

    public static ArmoryStatService getStatService() {
        return STAT_SERVICE;
    }
}
