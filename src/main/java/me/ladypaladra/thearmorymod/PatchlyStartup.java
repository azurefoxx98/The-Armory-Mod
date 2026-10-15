package me.ladypaladra.thearmorymod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;
import com.riprod.patchly.store.OverridePackRegistrar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;

final class PatchlyStartup {

    private PatchlyStartup() {
    }

    /**
     * Removes generated output before the engine can load it as a second asset pack.
     * A live plugin reload keeps its registered pack intact.
     */
    static void prepare(JavaPlugin plugin) {
        if (PluginManager.get().getState() != PluginState.SETUP) {
            return;
        }

        try {
            if (cleanGeneratedPack(PluginManager.MODS_PATH,
                    plugin.getManifest().getGroup(), plugin.getManifest().getName())) {
                TheArmoryMod.LOGGER.atInfo().log("Removed generated patch output left by an earlier server run.");
            }
        } catch (IOException | RuntimeException failure) {
            TheArmoryMod.LOGGER.atWarning().withCause(failure).log("Could not remove stale generated patch output.");
        }
    }

    static boolean cleanGeneratedPack(Path modsDirectory, String group, String name) throws IOException {
        Path modsRoot = modsDirectory.toAbsolutePath().normalize();
        Path generated = modsRoot.resolve(group + "_" + name + OverridePackRegistrar.OVERRIDE_PACK_SUFFIX).normalize();
        if (!modsRoot.equals(generated.getParent())) {
            throw new IOException("Generated patch path is outside the mods directory.");
        }
        if (!Files.exists(generated, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!Files.isDirectory(generated, LinkOption.NOFOLLOW_LINKS)
                || !generated.toRealPath().equals(modsRoot.toRealPath().resolve(generated.getFileName()))) {
            throw new IOException("Generated patch path is not a directory inside the mods directory.");
        }

        Path manifestPath = generated.resolve("manifest.json");
        JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
        if (!group.equals(manifest.get("Group").getAsString())
                || !(name + OverridePackRegistrar.OVERRIDE_PACK_SUFFIX).equals(manifest.get("Name").getAsString())
                || !manifest.get("IncludesAssetPack").getAsBoolean()
                || manifest.has("Main")) {
            throw new IOException("The directory does not identify itself as this plugin's generated patch pack.");
        }

        // Files.walk does not follow links, so a generated entry cannot extend this deletion elsewhere.
        try (var paths = Files.walk(generated)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
        return true;
    }
}
