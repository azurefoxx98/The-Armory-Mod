package me.ladypaladra.thearmorymod.ui;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The one home for walking an engine {@link Message} tree, shared by the benches and
 * by the scribing feature. It lives in this package so that no feature package has to
 * depend on another.
 */
public final class MessageTrees {

    /**
     * Shared limit for every walk of an engine message tree in this mod. Our parser
     * flattens its own trees to one level, so this only affects trees written by other
     * code and keeps a pathological tree from overflowing the stack inside the engine tick.
     */
    public static final int MAX_DEPTH = 16;

    private MessageTrees() {
    }

    /**
     * Returns only the visible characters in a message, without styling or markup, so they
     * can be compared with text a player typed.
     *
     * <p>Do not use getAnsiMessage for this. It is a console renderer and inserts ANSI
     * escape codes between runs. A search for "dawnbreaker" would then miss a name where
     * "Dawn" and "breaker" have different colours. getRawText is not suitable either,
     * because it returns only the root run.</p>
     */
    @Nonnull
    public static String plainText(@Nullable Message message) {
        if (message == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        appendPlain(message.getFormattedMessage(), text, 0);
        return text.toString();
    }

    private static void appendPlain(@Nullable FormattedMessage node, @Nonnull StringBuilder text, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }
        if (node.rawText != null) {
            text.append(node.rawText);
        }
        if (node.children != null) {
            for (FormattedMessage child : node.children) {
                appendPlain(child, text, depth + 1);
            }
        }
    }
}
