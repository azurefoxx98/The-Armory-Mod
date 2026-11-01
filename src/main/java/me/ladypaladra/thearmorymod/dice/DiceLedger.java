package me.ladypaladra.thearmorymod.dice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Remembers every die in flight and every die owed back, in a file of the plugin's own, so no die is lost.
 *
 * <p>The engine never saves a thrown object, so a die in the air when the server stops would simply vanish. Every
 * thrown item is written here as flying, and struck off once its dice lie on the ground, where the world saves them.
 * When the world loses one instead (it fell out of the world, its chunk unloaded mid-flight, the server stopped) it
 * becomes owed, and its owner gets it back on the spot or at their next login, wherever they are. A file rather than
 * the owner's own data, because the owner may be offline or in another world when their die is lost.</p>
 *
 * <p>It also remembers each D100 pair taken back while one of its two dice lay in an unloaded chunk, so that die is
 * cleared when its chunk loads rather than picked up for a second D100.</p>
 */
public final class DiceLedger {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Armory dice ledger");
        t.setDaemon(true);
        return t;
    });

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Set<UUID> collectedPairs = new HashSet<>();
    private volatile boolean anyOwed;

    private DiceLedger(@Nonnull Path file) {
        this.file = file;
    }

    /**
     * Opens the ledger. A die recorded as flying when the server stopped cannot still be in the air: it is owed.
     */
    @Nonnull
    public static DiceLedger open(@Nonnull Path file) {
        DiceLedger ledger = new DiceLedger(file);
        if (Files.exists(file)) {
            try {
                Saved saved = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
                if (saved != null) {
                    if (saved.entries != null) {
                        for (Entry e : saved.entries) {
                            if (e.id != null && e.owner != null && e.item != null) {
                                e.owed = true;
                                ledger.entries.put(e.id, e);
                            }
                        }
                    }
                    if (saved.collectedPairs != null) {
                        ledger.collectedPairs.addAll(saved.collectedPairs);
                    }
                }
            } catch (IOException | JsonParseException e) {
                // Kept aside rather than overwritten, so dice it owes can still be found by hand.
                LOGGER.atSevere().withCause(e).log("The dice ledger %s could not be read; it is kept as .unreadable", file);
                try {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".unreadable"),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moveFailed) {
                    LOGGER.atSevere().withCause(moveFailed).log("The unreadable dice ledger could not be set aside");
                }
            }
        }
        synchronized (ledger) {
            ledger.anyOwed = !ledger.entries.isEmpty();
            ledger.save();
        }
        return ledger;
    }

    public synchronized void flying(@Nonnull UUID id, @Nonnull UUID owner, @Nonnull String item) {
        Entry e = new Entry();
        e.id = id;
        e.owner = owner;
        e.item = item;
        entries.put(id, e);
        save();
    }

    public synchronized void landed(@Nonnull UUID id) {
        if (entries.remove(id) != null) {
            save();
        }
    }

    public synchronized void lost(@Nonnull UUID id) {
        Entry e = entries.get(id);
        if (e != null && !e.owed) {
            e.owed = true;
            anyOwed = true;
            save();
        }
    }

    /**
     * Owes {@code owner} a die that had already landed and was then taken by the world instead of a player: a die laid
     * down where no chunk can hold it, or removed by anything but a pickup.
     */
    public synchronized void owe(@Nonnull UUID owner, @Nonnull String item) {
        Entry e = new Entry();
        e.id = UUID.randomUUID();
        e.owner = owner;
        e.item = item;
        e.owed = true;
        entries.put(e.id, e);
        anyOwed = true;
        save();
    }

    /**
     * A cheap test a system can run every tick before asking for anything.
     */
    public boolean anyOwed() {
        return anyOwed;
    }

    /**
     * Takes every item owed to {@code owner}, striking them off. The caller gives them back.
     */
    @Nonnull
    public synchronized List<String> takeOwed(@Nonnull UUID owner) {
        List<String> items = new ArrayList<>();
        entries.values().removeIf(e -> {
            if (e.owed && owner.equals(e.owner)) {
                items.add(e.item);
                return true;
            }
            return false;
        });
        if (!items.isEmpty()) {
            anyOwed = entries.values().stream().anyMatch(e -> e.owed);
            save();
        }
        return items;
    }

    public synchronized void pairCollected(@Nonnull UUID pairId) {
        if (collectedPairs.add(pairId)) {
            save();
        }
    }

    /**
     * True, once, for a die whose pair was already taken back: it is cleared instead of lying there.
     */
    public synchronized boolean consumeCollected(@Nonnull UUID pairId) {
        if (collectedPairs.remove(pairId)) {
            save();
            return true;
        }
        return false;
    }

    /**
     * Writes what is pending and stops the writer. Called as the plugin shuts down.
     */
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.atWarning().log("The dice ledger took more than 10 seconds to write");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void save() {
        Saved saved = new Saved();
        saved.entries = new ArrayList<>(entries.size());
        for (Entry e : entries.values()) {
            saved.entries.add(e.copy());
        }
        saved.collectedPairs = new ArrayList<>(collectedPairs);
        String text = GSON.toJson(saved);
        if (writer.isShutdown()) {
            write(text);
        } else {
            writer.execute(() -> write(text));
        }
    }

    /**
     * Writes beside the file and moves it over, so a crash mid-write leaves the last good ledger.
     */
    private void write(String text) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.atSevere().withCause(e).log("The dice ledger %s could not be written", file);
        }
    }

    static final class Entry {
        UUID id;
        UUID owner;
        String item;
        boolean owed;

        Entry copy() {
            Entry c = new Entry();
            c.id = id;
            c.owner = owner;
            c.item = item;
            c.owed = owed;
            return c;
        }
    }

    static final class Saved {
        List<Entry> entries;
        List<UUID> collectedPairs;
    }
}
