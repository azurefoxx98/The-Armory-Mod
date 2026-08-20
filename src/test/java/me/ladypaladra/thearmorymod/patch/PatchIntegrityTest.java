package me.ladypaladra.thearmorymod.patch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protects the patch migration from failures that otherwise look like valid assets in source.
 *
 * <p>A malformed patch is dropped at load with the rest of the file's changes and never reaches
 * a player. A patch shadowed by a JSON file is quieter still because our own frozen copy wins.
 * These checks keep both failures visible at build time.</p>
 */
class PatchIntegrityTest {

    private static final Path RESOURCE_ROOT = Path.of("src", "main", "resources");

    @Test
    void everyPatchParsesAsJson() throws IOException {
        List<Path> patches = patchFiles();
        List<String> malformed = new ArrayList<>();

        for (Path patch : patches) {
            try (Reader reader = Files.newBufferedReader(patch, StandardCharsets.UTF_8)) {
                JsonParser.parseReader(reader).getAsJsonObject();
            } catch (IOException | RuntimeException exception) {
                malformed.add(relativePath(patch) + ": " + exception.getMessage());
            }
        }

        assertEquals(
                List.of(),
                malformed,
                "A malformed patch is dropped at load with the rest of the file's changes and "
                        + "never reaches a player."
        );
    }

    @Test
    void noPatchShadowsAJsonWeAlsoShip() throws IOException {
        List<Path> patches = patchFiles();
        List<String> offenders = new ArrayList<>();

        for (Path patch : patches) {
            String fileName = patch.getFileName().toString();
            Path json = patch.resolveSibling(
                    fileName.substring(0, fileName.length() - ".patch".length()) + ".json"
            );
            if (Files.isRegularFile(json)) {
                offenders.add(relativePath(patch) + " shadows " + relativePath(json));
            }
        }

        assertEquals(
                List.of(),
                offenders,
                "These patches have a JSON file at the same resource path. Our own copy wins "
                        + "and the patch silently does nothing, which restores the exact failure "
                        + "this migration exists to remove."
        );
    }

    @Test
    void everyPatchIsANonEmptyObject() throws IOException {
        List<Path> patches = patchFiles();
        List<String> empty = new ArrayList<>();

        for (Path patch : patches) {
            try (Reader reader = Files.newBufferedReader(patch, StandardCharsets.UTF_8)) {
                JsonObject body = JsonParser.parseReader(reader).getAsJsonObject();
                if (body.isEmpty()) {
                    empty.add(relativePath(patch));
                }
            }
        }

        assertEquals(
                List.of(),
                empty,
                "These patches are empty objects. A patch that changes nothing is almost "
                        + "certainly a mistake in whatever produced it."
        );
    }

    private static List<Path> patchFiles() throws IOException {
        assertTrue(
                Files.isDirectory(RESOURCE_ROOT),
                "Resource root " + RESOURCE_ROOT.toAbsolutePath() + " not found, so this gate "
                        + "proved nothing. An empty scan that reports success is the failure mode "
                        + "this check must never have."
        );

        List<Path> patches;
        try (Stream<Path> files = Files.walk(RESOURCE_ROOT)) {
            patches = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".patch"))
                    .toList();
        }

        // A scan of nothing passes for the wrong reason. Zero patches means the walk is broken
        // or the migration disappeared, not that the resources are clean.
        assertTrue(
                patches.size() > 0,
                "Scanned no patch files at all, so this result passes for the wrong reason."
        );
        return patches;
    }

    private static String relativePath(Path path) {
        return RESOURCE_ROOT.relativize(path).toString();
    }
}
