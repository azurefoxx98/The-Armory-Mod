package me.ladypaladra.thearmorymod;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import me.ladypaladra.thearmorymod.parry.ParryModule;

import javax.annotation.Nonnull;

/**
 * @author LadyPaladra
 * @version 1.14.0
 */
@SuppressWarnings("unused")
public class TheArmoryMod extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public TheArmoryMod(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("Setting up...");
        ParryModule.register(this);
        LOGGER.atInfo().log("Setup complete!");
    }

    @Override
    protected void start() {
        LOGGER.atInfo().log("Started!");
    }

    @Override
    protected void shutdown() {
        LOGGER.atInfo().log("Shutting down...");
    }
}