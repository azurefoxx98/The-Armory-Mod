package me.ladypaladra.thearmorymod.metrics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Makes the two HStats review boundaries mechanical. The third-party client may differ from
 * upstream only in its package line, and production code may reach it only through
 * {@link ArmoryMetrics}.
 *
 * <p>The ingest credential has a separate boundary. It is generated into the build tree rather
 * than stored under main resources, because anything in that source tree would be committed to
 * this public repository.</p>
 */
class HStatsClientIntegrityTest {

    private static final Path CLIENT = Path.of(
            "src", "main", "java", "me", "ladypaladra", "thearmorymod", "metrics",
            "HStats.java"
    );

    @Test
    void clientMatchesUpstreamExceptForThePackageLine()
            throws IOException, NoSuchAlgorithmException {
        assertTrue(
                Files.isRegularFile(CLIENT),
                "HStats.java is missing, so this gate would pass for the wrong reason."
        );

        // Line endings are normalised because this repository has no line-ending convention.
        // A raw byte comparison would fail for a reason that has nothing to do with the licence.
        String source = new String(Files.readAllBytes(CLIENT), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replaceFirst("(?m)^package .*?;$", "package com.al3x;");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                source.getBytes(StandardCharsets.UTF_8)
        );
        String actual = HexFormat.of().formatHex(digest);

        assertEquals(
                "0f977fe3566230ac77634be959a3f47c1c28bd123612ad6a0ad2654906cdc909",
                actual,
                "The third-party client has been modified. The licence permits changing the "
                        + "package line and nothing else. Altering what it sends is a bannable "
                        + "offence with the service. Restore the file instead of updating this "
                        + "hash."
        );
    }

    @Test
    void onlyTheFacadeReachesTheClient() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        List<String> offenders = new ArrayList<>();
        int scanned = 0;

        try (Stream<Path> files = Files.walk(sourceRoot)) {
            List<Path> javaFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();

            for (Path path : javaFiles) {
                scanned++;
                String fileName = path.getFileName().toString();
                if (fileName.equals("HStats.java") || fileName.equals("ArmoryMetrics.java")) {
                    continue;
                }
                String body = Files.readString(path, StandardCharsets.UTF_8);
                if (body.contains("HStats")) {
                    offenders.add(sourceRoot.relativize(path).toString());
                }
            }
        }

        // A gate that scans nothing passes for the wrong reason. Zero files means the walk is
        // broken, not that the codebase is clean.
        assertTrue(scanned > 0, "Scanned no Java files, so this gate passed for the wrong reason.");

        assertEquals(
                List.of(),
                offenders,
                "These classes reach HStats directly. Route the call through ArmoryMetrics so "
                        + "there stays one place to review."
        );
    }

    @Test
    void theIngestKeyIsNotInTheSourceTree() throws IOException {
        Path resourceRoot = Path.of("src", "main", "resources");

        if (Files.exists(resourceRoot)) {
            try (Stream<Path> files = Files.walk(resourceRoot)) {
                List<String> offenders = files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equals("hstats.properties"))
                        .map(path -> resourceRoot.relativize(path).toString())
                        .toList();
                assertEquals(
                        List.of(),
                        offenders,
                        "The key is injected at build time. A copy in the source tree would be "
                                + "committed to a public repository."
                );
            }
        }

        assertTrue(
                Files.exists(resourceRoot),
                "The main resource tree does not exist, so this gate passed by scanning nothing."
        );
    }

    /**
     * Checks for the credential itself rather than for a file that might hold it. The test above
     * only catches a copy that kept the generated file name, which leaves the obvious mistake of
     * pasting the value straight into a class. At test time the generated resource is on the
     * classpath, so the real key can be read and searched for.
     *
     * <p>A build with no key skips this rather than passing, because searching for an empty
     * string would match every file and report success for the wrong reason.</p>
     */
    @Test
    void theIngestKeyAppearsInNoSourceFile() throws IOException {
        String key = "";
        try (InputStream stream = HStatsClientIntegrityTest.class.getResourceAsStream(
                "hstats.properties"
        )) {
            if (stream != null) {
                Properties properties = new Properties();
                properties.load(stream);
                key = properties.getProperty("key", "").trim();
            }
        }

        assumeFalse(
                key.isEmpty(),
                "This build carries no key, so there is nothing to search for."
        );

        List<String> offenders = new ArrayList<>();
        int scanned = 0;

        for (Path root : List.of(Path.of("src"), Path.of("build.gradle"))) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path path : files.filter(Files::isRegularFile).toList()) {
                    scanned++;
                    // Read as ISO-8859-1 rather than UTF-8. This walk covers the whole source
                    // tree, which is mostly textures, and decoding a PNG as UTF-8 throws. This
                    // charset maps every byte to one character and never throws, and the key is
                    // ASCII, so a match still means what it looks like.
                    String body = new String(
                            Files.readAllBytes(path), StandardCharsets.ISO_8859_1
                    );
                    if (body.contains(key)) {
                        offenders.add(path.toString());
                    }
                }
            }
        }

        assertTrue(scanned > 0, "Scanned no files, so this gate passed for the wrong reason.");

        // The message deliberately names the files and never the value, so a failing build log
        // does not publish the credential it exists to protect.
        assertEquals(
                List.of(),
                offenders,
                "These files contain the HStats ingest key. It is injected at build time and "
                        + "must never be committed to this public repository."
        );
    }
}
