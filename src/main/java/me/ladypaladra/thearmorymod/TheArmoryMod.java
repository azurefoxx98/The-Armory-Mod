package me.ladypaladra.thearmorymod;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import me.ladypaladra.thearmorymod.armorstand.ArmorStandModule;
import me.ladypaladra.thearmorymod.krafter.KrafterModule;
import me.ladypaladra.thearmorymod.largebench.LargeStructuralCraftingModule;
import me.ladypaladra.thearmorymod.parry.ParryModule;
import me.ladypaladra.thearmorymod.scorpianflail.systems.ScorpianFlailPoisonSystem;
import me.ladypaladra.thearmorymod.stats.ArmoryStatsModule;

import javax.annotation.Nonnull;

@SuppressWarnings("unused")
public class TheArmoryMod extends JavaPlugin {

    public static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public TheArmoryMod(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("Setting up...");

        ParryModule.register(this);
        ArmoryStatsModule.register(this);
        // ArmorTailoringModule.register(this);
        LargeStructuralCraftingModule.register(this);
        KrafterModule.register(this);

        // ArmorStandModule.register(this);

        getEntityStoreRegistry().registerSystem(new ScorpianFlailPoisonSystem());
        // getEntityStoreRegistry().registerSystem(new EngineeringRigMobilitySystem());

        LOGGER.atInfo().log("Setup complete!");
    }

    @Override
    protected void start() {
        LOGGER.atInfo().log("Started!");
    }

    @Override
    protected void shutdown() {
        LOGGER.atInfo().log("Shutting down...");

        ArmorStandModule.shutdown(this);
    }
}