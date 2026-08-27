package me.ladypaladra.thearmorymod.ui;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Flattens engine messages into tooltip runs.
 *
 * <p>A child run does not inherit its parent's colour or styling. Our inscriptions are
 * flattened to one level by ScribingMarkup, so every run carries its own style and this
 * is exact for them. A deeper tree written by another mod renders each unstyled run in
 * the default description colour instead of an inherited one. The shared depth limit
 * applies to this walk, and anything past it is not drawn.</p>
 */
public final class TooltipMessages {

    private TooltipMessages() {
    }

    @Nonnull
    public static List<TooltipMarkup.Run> flatten(
            @Nullable Message message,
            @Nullable String translated
    ) {
        if (message == null) {
            return List.of();
        }
        if (message.getMessageId() != null) {
            return translated == null || translated.isEmpty()
                    ? List.of()
                    : List.of(new TooltipMarkup.Run(translated, false, false, null));
        }

        List<TooltipMarkup.Run> runs = new ArrayList<>();
        append(message.getFormattedMessage(), runs, 0);
        return runs;
    }

    private static void append(
            @Nullable FormattedMessage node,
            @Nonnull List<TooltipMarkup.Run> runs,
            int depth
    ) {
        if (node == null) {
            return;
        }
        if (depth > MessageTrees.MAX_DEPTH) {
            // This is a read-only display path, so dropping the remainder is correct.
            return;
        }
        if (node.rawText != null && !node.rawText.isEmpty()) {
            runs.add(new TooltipMarkup.Run(
                    node.rawText,
                    Boolean.TRUE.equals(node.bold),
                    Boolean.TRUE.equals(node.italic),
                    node.color
            ));
        }
        if (node.children != null) {
            for (FormattedMessage child : node.children) {
                append(child, runs, depth + 1);
            }
        }
    }
}
