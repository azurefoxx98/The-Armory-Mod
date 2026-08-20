package me.ladypaladra.thearmorymod.ui;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class LastPicked {

    // This is deliberately session-only convenience state. Losing it on restart costs
    // one click, so it does not belong in a persisted player component or on disk.
    // Entries are never evicted. The map holds one small record per player per bench for
    // the server's lifetime, bounded by the player count and only a few dozen bytes each.
    private static final ConcurrentMap<Key, Pick> PICKS = new ConcurrentHashMap<>();

    private LastPicked() {
    }

    public record Pick(int section, short slot, @Nonnull String itemId) {
    }

    private record Key(@Nonnull UUID player, @Nonnull String bench) {
    }

    public static void remember(
            @Nonnull UUID player,
            @Nonnull String bench,
            @Nonnull Pick pick
    ) {
        PICKS.put(new Key(player, bench), pick);
    }

    @Nullable
    public static Pick recall(@Nonnull UUID player, @Nonnull String bench) {
        return PICKS.get(new Key(player, bench));
    }

    public static void forget(@Nonnull UUID player, @Nonnull String bench) {
        PICKS.remove(new Key(player, bench));
    }
}
