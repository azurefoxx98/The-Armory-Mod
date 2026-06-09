package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import me.ladypaladra.thearmorymod.stats.systems.ArrowDamageBonusSystem;
import me.ladypaladra.thearmorymod.stats.systems.GroundMoveSpeedModifierSystem;
import me.ladypaladra.thearmorymod.stats.systems.JumpHeightModifierSystem;

public final class ArmoryStatsModule {

    private static final ArmoryStatService STAT_SERVICE = new ArmoryStatService();

    private ArmoryStatsModule() {
    }

    public static void register(JavaPlugin plugin) {
        plugin.getEntityStoreRegistry().registerSystem(new ArrowDamageBonusSystem(STAT_SERVICE));

        plugin.getEntityStoreRegistry().registerSystem(new GroundMoveSpeedModifierSystem());
        plugin.getEntityStoreRegistry().registerSystem(new JumpHeightModifierSystem());
    }

    public static ArmoryStatService getStatService() {
        return STAT_SERVICE;
    }
}