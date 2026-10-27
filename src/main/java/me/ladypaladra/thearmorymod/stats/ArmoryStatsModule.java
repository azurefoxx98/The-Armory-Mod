package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.stats.systems.ArrowDamageBonusSystem;
import me.ladypaladra.thearmorymod.stats.systems.GroundMoveSpeedModifierSystem;
import me.ladypaladra.thearmorymod.stats.systems.JumpHeightModifierSystem;
import me.ladypaladra.thearmorymod.stats.systems.SwimSpeedModifierSystem;

public final class ArmoryStatsModule {

    private static final ArmoryStatService STAT_SERVICE = new ArmoryStatService();

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
    }

    public static ArmoryStatService getStatService() {
        return STAT_SERVICE;
    }
}
