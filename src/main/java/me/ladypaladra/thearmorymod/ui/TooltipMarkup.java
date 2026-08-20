package me.ladypaladra.thearmorymod.ui;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Serializes text runs into the markup parsed by an item slot's Description field.
 *
 * <p>Every run is wrapped, including an otherwise unstyled run. In-game measurement
 * showed that bare text is attached to the parsed tree's root and rendered before tagged
 * children, reordering the description. Giving every run a color tag keeps the flattened
 * source order intact; {@code #696969} is the native tooltip description color.</p>
 */
public final class TooltipMarkup {

    private static final String DEFAULT_COLOR = "#696969";

    private TooltipMarkup() {
    }

    public record Run(
            @Nonnull String text,
            boolean bold,
            boolean italic,
            @Nullable String color
    ) {
    }

    @Nonnull
    public static String wrap(@Nullable List<Run> runs) {
        if (runs == null || runs.isEmpty()) {
            return "";
        }

        StringBuilder markup = new StringBuilder();
        for (Run run : runs) {
            if (run == null || run.text() == null || run.text().isEmpty()) {
                continue;
            }
            markup.append(wrapRun(run));
        }
        return markup.toString();
    }

    @Nonnull
    private static String wrapRun(@Nonnull Run value) {
        StringBuilder run = new StringBuilder();
        run.append("<color is=\"")
                .append(value.color() != null ? value.color() : DEFAULT_COLOR)
                .append("\">");
        if (value.bold()) {
            run.append("<b>");
        }
        if (value.italic()) {
            run.append("<i>");
        }
        run.append(value.text());
        if (value.italic()) {
            run.append("</i>");
        }
        if (value.bold()) {
            run.append("</b>");
        }
        return run.append("</color>").toString();
    }
}
