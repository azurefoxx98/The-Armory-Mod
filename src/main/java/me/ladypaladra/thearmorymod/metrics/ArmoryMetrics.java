package me.ladypaladra.thearmorymod.metrics;

import com.hypixel.hytale.logger.HytaleLogger;

import java.io.InputStream;
import java.util.Properties;

/**
 * Keeps construction of the HStats client in one place. This is the only class allowed to
 * construct it, which gives one file to review against two rules: the key never enters source
 * control and the third-party client is never modified.
 *
 * <p>The key is injected at build time and is absent from the repository on purpose. HStats
 * issues a public identifier that is safe to publish and a private ingest credential. This
 * repository is public, so committing the private value would hand a working credential to
 * anyone reading the source. The credential still has to ship inside the jar because the
 * service has no other way to work, and anyone who unzips a release can read it. Keeping it out
 * of source means it does not sit in public history that cannot be rewritten later.</p>
 *
 * <p>A build without the key is a normal build. It reports nothing, which is the safe direction
 * to fail.</p>
 *
 * <p>The client transmits a random per-server identifier, the online player count, the OS name
 * and version, the Java version and the core count. It sends no player name, player identity,
 * address, world data or coordinates.</p>
 *
 * <p>Server owners can opt out by editing {@code hstats-server-uuid.txt} in the server
 * directory. Every HStats mod on that server shares the file, so one edit opts out of all of
 * them.</p>
 */
public final class ArmoryMetrics {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private ArmoryMetrics() {
    }

    /**
     * Start reporting only when this build contains a key. The catch uses Throwable rather
     * than RuntimeException because the realistic failure is a LinkageError if the engine moves
     * {@code HytaleServer.SCHEDULED_EXECUTOR} or {@code Universe}. LinkageError is an Error
     * rather than an exception, so a RuntimeException catch would let it leave this call site
     * and enter the engine.
     */
    public static void start() {
        try {
            Properties properties = new Properties();
            try (InputStream stream = ArmoryMetrics.class.getResourceAsStream(
                    "hstats.properties"
            )) {
                // A missing resource and an empty key are different faults and are reported
                // differently on purpose. The build always generates this file, with an empty
                // key when none was supplied, so its absence means the generating task did not
                // run rather than that nobody supplied a key. Collapsing the two would hide a
                // broken build behind the message for a normal one.
                if (stream == null) {
                    LOGGER.atWarning().log(
                            "HStats configuration is missing from this build, so nothing will be "
                                    + "reported. The build did not generate it."
                    );
                    return;
                }
                properties.load(stream);
            }

            String key = properties.getProperty("key");
            if (key == null || key.trim().isEmpty()) {
                LOGGER.atInfo().log(
                        "HStats reporting is not configured for this build and will report nothing."
                );
                return;
            }
            key = key.trim();

            String version = properties.getProperty("version");
            if (version == null || version.trim().isEmpty()) {
                version = "Unknown";
            } else {
                version = version.trim();
            }

            // The client schedules its own repeating task on the engine executor and exposes no
            // way to cancel it. We cannot add one because the third-party file may not be modified.
            new HStats(key, version);
            LOGGER.atInfo().log("HStats reporting is enabled for version %s.", version);
        } catch (Throwable throwable) {
            LOGGER.atWarning().withCause(throwable).log(
                    "HStats failed to start. The Armory continues without server metrics."
            );
        }
    }
}
