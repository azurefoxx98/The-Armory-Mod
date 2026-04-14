package me.ladypaladra.thearmorymod;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * @author LadyPaladra
 * @version 1.14.0
 */
public class TheArmoryMod extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static TheArmoryMod instance;

    public TheArmoryMod(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static TheArmoryMod getInstance() {
        return instance;
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("Setting up...");


        LOGGER.atInfo().log("Setup complete!");
    }

    @Override
    protected void start() {
        LOGGER.atInfo().log("Started!");
    }

    @Override
    protected void shutdown() {
        LOGGER.atInfo().log("Shutting down...");
        instance = null;
    }
}