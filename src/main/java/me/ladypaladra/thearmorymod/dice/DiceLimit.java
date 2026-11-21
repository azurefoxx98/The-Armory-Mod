package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;

import javax.annotation.Nonnull;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * How many dice one player may have out in the world at once: flying or lying where they landed. A D100's pair is one.
 *
 * <p>The limit never refuses a player their dice. A throw past it calls the player's oldest lying dice back to them
 * first, so the world never holds more than the limit and nobody has to walk back to a die they left behind, or is
 * stuck because one lies somewhere they can no longer reach.</p>
 *
 * <p>The server owner sets the number for everyone in config.json, and may name more numbers there, each given to the
 * players who hold its permission. A player gets the highest number they are entitled to.</p>
 */
public final class DiceLimit {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * A limit below zero is no limit.
     */
    static final int NONE = -1;

    /**
     * Holding this lifts the limit. Operators hold every permission, so they are never limited.
     */
    public static final String UNLIMITED_PERMISSION = "thearmory.dice.unlimited";

    /**
     * A limit the owner named "vip" in config.json is given by this prefix followed by "vip".
     */
    public static final String NAMED_PREFIX = "thearmory.dice.limit.";

    private static volatile int everyone = 10;
    @Nonnull
    private static volatile Map<String, Integer> byPermission = Map.of();

    private DiceLimit() {
    }

    /**
     * Takes the owner's settings and registers each permission, so permission tools list them.
     */
    static void configure(int forEveryone, @Nonnull Map<String, Integer> named) {
        everyone = forEveryone;
        Map<String, Integer> nodes = new LinkedHashMap<>();
        register(UNLIMITED_PERMISSION);
        for (Map.Entry<String, Integer> entry : named.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String node = NAMED_PREFIX + entry.getKey().trim().toLowerCase(Locale.ROOT);
            // A name the engine does not accept as a permission must never stop the server from starting.
            if (register(node)) {
                nodes.put(node, entry.getValue());
            } else {
                LOGGER.atWarning().log("config.json: the dice limit named \"%s\" is ignored, %s is not a valid permission",
                        entry.getKey(), node);
            }
        }
        byPermission = Map.copyOf(nodes);
    }

    private static boolean register(@Nonnull String node) {
        try {
            PermissionsModule.registerPermission(node);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The limit of a player who holds the permissions {@code holds} answers yes to.
     */
    static int of(@Nonnull Predicate<String> holds) {
        return resolve(everyone, byPermission, holds);
    }

    /**
     * The highest of everyone's limit and the limits of the permissions held, or {@link #NONE} when any of them is no
     * limit.
     */
    static int resolve(int forEveryone, @Nonnull Map<String, Integer> byNode, @Nonnull Predicate<String> holds) {
        if (forEveryone < 0 || holds.test(UNLIMITED_PERMISSION)) {
            return NONE;
        }
        int limit = forEveryone;
        for (Map.Entry<String, Integer> entry : byNode.entrySet()) {
            if (!holds.test(entry.getKey())) {
                continue;
            }
            if (entry.getValue() < 0) {
                return NONE;
            }
            limit = Math.max(limit, entry.getValue());
        }
        return limit;
    }

    /**
     * Makes room for {@code wanted} more dice of {@code owner} under {@code limit}, calling the oldest lying ones back
     * through {@code recall}, and returns how many may be thrown. Dice still in the air cannot be called back, so a
     * player whose dice are all flying throws fewer, or none, until they land.
     */
    static int makeRoom(@Nonnull DiceLedger ledger, @Nonnull UUID owner, int wanted, int limit,
                        @Nonnull Consumer<UUID> recall) {
        if (limit < 0) {
            return wanted;
        }
        int fits = Math.min(wanted, limit);
        int out = ledger.out(owner);
        while (fits > 0 && out + fits > limit) {
            UUID oldest = ledger.recallOldest(owner);
            if (oldest == null) {
                break;
            }
            recall.accept(oldest);
            out--;
        }
        return Math.max(0, Math.min(fits, limit - out));
    }
}
