package me.ladypaladra.thearmorymod.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that the shared UI package does not depend on a feature package.
 *
 * <p>The benches share this package. An import from here into one feature makes every
 * bench depend on that feature, even when the shared class itself is feature-neutral.</p>
 *
 * <p>This rule fails the build because a package boundary left only as an intention can
 * disappear quietly when a convenient feature utility is imported.</p>
 */
class UiPackageIsolationTest {

    private static final String MOD_PACKAGE = "me.ladypaladra.thearmorymod.";
    private static final String UI_PACKAGE = MOD_PACKAGE + "ui.";

    @Test
    void sharedUiDoesNotImportFeaturePackages() throws IOException {
        Path sourceRoot = Path.of(
                "src", "main", "java", "me", "ladypaladra", "thearmorymod", "ui"
        );
        assertTrue(
                Files.isDirectory(sourceRoot),
                "UI source root " + sourceRoot.toAbsolutePath() + " not found, so this gate "
                        + "proved nothing. A missing package must fail instead of looking like "
                        + "a package with no forbidden imports."
        );

        List<String> offenders = new ArrayList<>();
        int scanned = 0;

        try (Stream<Path> files = Files.walk(sourceRoot)) {
            List<Path> javaFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();

            for (Path path : javaFiles) {
                scanned++;
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                // Every line is checked, not only the import lines, because naming a feature
                // class in full reaches it just as surely as importing it does. The package
                // declaration is the one line that names this package without depending on
                // anything, so it is the only line skipped.
                boolean reachesFeature = lines.stream()
                        .map(String::stripLeading)
                        .filter(line -> !line.startsWith("package "))
                        .anyMatch(line -> line.contains(MOD_PACKAGE) && !line.contains(UI_PACKAGE));
                if (reachesFeature) {
                    offenders.add(sourceRoot.relativize(path).toString());
                }
            }
        }

        // An empty walk cannot prove this boundary. If no Java files were found, the gate is
        // pointed at the wrong place or the source layout changed, and success would be false.
        assertTrue(scanned > 0, "Scanned no UI Java files, so this result is meaningless.");

        assertEquals(
                List.of(),
                offenders,
                "These UI classes reach into a feature package, by import or by naming the "
                        + "class in full. The benches share ui, so a dependency from ui into a "
                        + "feature package makes every bench depend on that feature. Move the "
                        + "shared part into ui instead, which is what this package is for."
        );
    }
}
