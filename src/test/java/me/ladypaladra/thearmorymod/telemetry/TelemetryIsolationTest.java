package me.ladypaladra.thearmorymod.telemetry;

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
 * Asserts that the telemetry runtime is reachable from exactly one class.
 *
 * <p>This is the privacy rule made mechanical. The project descriptor's allowlist drops any
 * field we never declared, but it cannot stop us putting a player name into a field we did
 * declare as a string. So the rule is that every telemetry call goes through
 * {@link ArmoryTelemetry}, which gives one file to review against that rule instead of a set
 * that grows quietly with every new call site.</p>
 *
 * <p>A rule written only in a document gets broken by the fifth call site. This one fails the
 * build instead, which is the entire reason it exists rather than a note in the blueprint.</p>
 */
class TelemetryIsolationTest {

    private static final String RUNTIME_PACKAGE = "com.alechilles";
    private static final String FACADE = "ArmoryTelemetry.java";

    @Test
    void onlyTheFacadeTouchesTheTelemetryRuntime() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        assertTrue(
                Files.isDirectory(sourceRoot),
                "Source root " + sourceRoot.toAbsolutePath() + " not found, so this gate proved "
                        + "nothing. An empty scan that reports success is the failure mode this "
                        + "check must never have."
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
                String body = Files.readString(path, StandardCharsets.UTF_8);
                if (!body.contains(RUNTIME_PACKAGE)) {
                    continue;
                }
                if (path.getFileName().toString().equals(FACADE)) {
                    continue;
                }
                offenders.add(sourceRoot.relativize(path).toString());
            }
        }

        // A gate that scans nothing passes for the wrong reason, which this project has already
        // shipped once in a different tool. Zero files means the walk is broken, not that the
        // codebase is clean.
        assertTrue(scanned > 0, "Scanned no Java files at all, so this result is meaningless.");

        assertEquals(
                List.of(),
                offenders,
                "These classes reach the telemetry runtime directly. Route them through "
                        + ArmoryTelemetry.class.getSimpleName() + " instead, so there stays "
                        + "exactly one place to review against the rule that player names, raw "
                        + "UUIDs, coordinates, tokens, chat and inventory contents never enter a "
                        + "recorded field."
        );
    }
}
