package me.ladypaladra.thearmorymod;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.riprod.patchly.PatchManager;

import me.ladypaladra.thearmorymod.alteration.AlterationModule;
import me.ladypaladra.thearmorymod.armorstand.ArmorStandModule;
import me.ladypaladra.thearmorymod.krafter.KrafterModule;
import me.ladypaladra.thearmorymod.metrics.ArmoryMetrics;
import me.ladypaladra.thearmorymod.parry.ParryModule;
import me.ladypaladra.thearmorymod.scorpianflail.systems.ScorpianFlailPoisonSystem;
import me.ladypaladra.thearmorymod.scribing.ScribingModule;
import me.ladypaladra.thearmorymod.stats.ArmoryStatsModule;
import me.ladypaladra.thearmorymod.telemetry.ArmoryTelemetry;

import javax.annotation.Nonnull;

@SuppressWarnings("unused")
public class TheArmoryMod extends JavaPlugin {

    public static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Patches must be merged before any module reads an asset, so the manager lives for the
     * plugin lifetime and is ready before setup begins.
     */
    private final PatchManager patchManager;

    public TheArmoryMod(@Nonnull JavaPluginInit init) {
        super(init);
        patchManager = new PatchManager(this);
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("Setting up...");
        ArmoryTelemetry.bootstrap(this);

        try {
            // Install before any module can read an asset. Keeping this inside the setup failure
            // boundary means a merge failure is captured and reported like every other setup
            // failure instead of escaping as an unexplained boot crash.
            patchManager.install();

            ParryModule.register(this);
            ArmoryStatsModule.register(this);
            // ArmorTailoringModule.register(this);
            AlterationModule.register(this);
            ScribingModule.register(this);
            KrafterModule.register(this);

            // ArmorStandModule.register(this);

            getEntityStoreRegistry().registerSystem(new ScorpianFlailPoisonSystem());
            // getEntityStoreRegistry().registerSystem(new EngineeringRigMobilitySystem());

            LOGGER.atInfo().log("Setup complete!");
            ArmoryTelemetry.breadcrumb("lifecycle", "Setup complete");
        } catch (Throwable throwable) {
            ArmoryTelemetry.setupFailure(throwable);
            throw throwable;
        }
    }

    @Override
    protected void start() {
        ArmoryTelemetry.start();

        try {
            ScribingModule.verifyPalette();
            ScribingModule.verifyDisplayKey();
            LOGGER.atInfo().log("Started!");
            // Report this server only after The Armory has actually started. A failure in either
            // verification above therefore reports nothing.
            ArmoryMetrics.start();
        } catch (Throwable throwable) {
            ArmoryTelemetry.startFailure(throwable);
            throw throwable;
        }
    }

    @Override
    protected void shutdown() {
        LOGGER.atInfo().log("Shutting down...");

        // Disabled because the matching register call in setup is disabled. Re-enable
        // both calls together.
        // ArmorStandModule.shutdown(this);
        ArmoryTelemetry.shutdown();
    }
}
