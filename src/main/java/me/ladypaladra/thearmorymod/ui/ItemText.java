package me.ladypaladra.thearmorymod.ui;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.util.Locale;

public final class ItemText {

    private ItemText() {
    }

    /**
     * Durability as the player reads it under the benches' slots, or an empty line for an
     * item that cannot wear out. Both benches now read the same because they used to
     * disagree on the same item.
     */
    @Nonnull
    public static String formatDurability(@Nonnull ItemStack stack) {
        if (stack.getMaxDurability() <= 0) {
            return "";
        }
        return displayDurability(stack.getDurability()) + "/" + displayDurability(stack.getMaxDurability());
    }

    /**
     * Rounds a durability for display. A stack with any wear left never shows zero,
     * because a piece that still works reading as broken would be a lie. This is a
     * presentation floor only, the stored value stays exactly what the transaction wrote.
     */
    private static long displayDurability(double value) {
        long rounded = Math.round(value);
        if (rounded < 1 && value > 0) {
            return 1;
        }
        return rounded;
    }

    public static boolean matchesSearch(
            @Nonnull ItemStack stack,
            @Nonnull String searchQuery,
            @Nonnull PlayerRef playerRef
    ) {
        if (searchQuery.isBlank()) {
            return true;
        }
        String needle = searchQuery.toLowerCase(Locale.ROOT);
        Message display = stack.getDisplayName();
        // Plain visible characters, so a name whose runs carry different colours still
        // matches a needle that crosses the boundary between them.
        String displayName = MessageTrees.plainText(display);
        if (display.getMessageId() != null) {
            String translated = I18nModule.get().getMessage(playerRef.getLanguage(), display.getMessageId());
            if (translated != null) {
                displayName = translated;
            }
        }
        return stack.getItemId().toLowerCase(Locale.ROOT).contains(needle)
                || displayName.toLowerCase(Locale.ROOT).contains(needle);
    }
}
