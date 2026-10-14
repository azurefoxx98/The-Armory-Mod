package me.ladypaladra.thearmorymod.ui;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Flattens engine messages into tooltip runs.
 *
 * <p>Children inherit colour and styling unless they explicitly override them.
 * Translation nodes resolve through the caller's catalog, including nested seal
 * markers. The shared depth limit bounds both child and message-parameter trees.</p>
 */
public final class TooltipMessages {

    private TooltipMessages() {
    }

    @Nonnull
    public static List<TooltipMarkup.Run> flatten(
            @Nullable Message message,
            @Nullable String translated
    ) {
        FormattedMessage root = message != null ? message.getFormattedMessage() : null;
        return flatten(message, node -> node == root ? translated : null);
    }

    @Nonnull
    public static List<TooltipMarkup.Run> flatten(
            @Nullable Message message,
            @Nonnull Function<FormattedMessage, String> translations
    ) {
        if (message == null) {
            return List.of();
        }
        List<TooltipMarkup.Run> runs = new ArrayList<>();
        append(message.getFormattedMessage(), runs, translations, false, false, null, 0);
        return runs;
    }

    private static void append(
            @Nullable FormattedMessage node,
            @Nonnull List<TooltipMarkup.Run> runs,
            @Nonnull Function<FormattedMessage, String> translations,
            boolean bold,
            boolean italic,
            @Nullable String color,
            int depth
    ) {
        if (node == null) {
            return;
        }
        if (depth > MessageTrees.MAX_DEPTH) {
            // This is a read-only display path, so dropping the remainder is correct.
            return;
        }
        boolean effectiveBold = node.bold != null ? node.bold : bold;
        boolean effectiveItalic = node.italic != null ? node.italic : italic;
        String effectiveColor = node.color != null ? node.color : color;
        String text = node.rawText != null ? node.rawText
                : node.messageId != null && parametersWithinDepth(node) ? translations.apply(node) : null;
        if (text != null && !text.isEmpty()) {
            runs.add(new TooltipMarkup.Run(
                    text,
                    effectiveBold,
                    effectiveItalic,
                    effectiveColor
            ));
        }
        if (node.children != null) {
            for (FormattedMessage child : node.children) {
                append(child, runs, translations, effectiveBold, effectiveItalic, effectiveColor, depth + 1);
            }
        }
    }

    private static boolean parametersWithinDepth(@Nonnull FormattedMessage node) {
        if (node.messageParams != null) {
            for (FormattedMessage parameter : node.messageParams.values()) {
                if (!withinTranslationDepth(parameter, 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean withinTranslationDepth(@Nullable FormattedMessage node, int depth) {
        if (node == null) {
            return true;
        }
        if (depth > MessageTrees.MAX_DEPTH) {
            return false;
        }
        if (node.children != null) {
            for (FormattedMessage child : node.children) {
                if (!withinTranslationDepth(child, depth + 1)) {
                    return false;
                }
            }
        }
        if (node.messageParams != null) {
            for (FormattedMessage parameter : node.messageParams.values()) {
                if (!withinTranslationDepth(parameter, depth + 1)) {
                    return false;
                }
            }
        }
        return true;
    }
}
