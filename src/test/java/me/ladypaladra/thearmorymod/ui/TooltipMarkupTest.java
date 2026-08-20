package me.ladypaladra.thearmorymod.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TooltipMarkupTest {

    @Test
    void nullListProducesEmptyMarkup() {
        assertEquals("", TooltipMarkup.wrap(null));
    }

    @Test
    void emptyListProducesEmptyMarkup() {
        assertEquals("", TooltipMarkup.wrap(List.of()));
    }

    @Test
    void runWithEmptyTextIsSkipped() {
        assertEquals(
                "<color is=\"#696969\">kept</color>",
                TooltipMarkup.wrap(List.of(
                        new TooltipMarkup.Run("", true, true, "#e8a93b"),
                        new TooltipMarkup.Run("kept", false, false, null)
                ))
        );
    }

    @Test
    void plainRunUsesTheNativeDescriptionColor() {
        assertEquals(
                "<color is=\"#696969\">plain</color>",
                TooltipMarkup.wrap(List.of(
                        new TooltipMarkup.Run("plain", false, false, null)
                ))
        );
    }

    @Test
    void colouredRunUsesItsOwnColor() {
        assertEquals(
                "<color is=\"#e8a93b\">gold</color>",
                TooltipMarkup.wrap(List.of(
                        new TooltipMarkup.Run("gold", false, false, "#e8a93b")
                ))
        );
    }

    @Test
    void boldRunNestsInsideColor() {
        assertEquals(
                "<color is=\"#696969\"><b>bold</b></color>",
                TooltipMarkup.wrap(List.of(
                        new TooltipMarkup.Run("bold", true, false, null)
                ))
        );
    }

    @Test
    void italicRunNestsInsideColor() {
        assertEquals(
                "<color is=\"#696969\"><i>italic</i></color>",
                TooltipMarkup.wrap(List.of(
                        new TooltipMarkup.Run("italic", false, true, null)
                ))
        );
    }

    @Test
    void boldItalicRunUsesDeterministicNesting() {
        assertEquals(
                "<color is=\"#696969\"><b><i>both</i></b></color>",
                TooltipMarkup.wrap(List.of(
                        new TooltipMarkup.Run("both", true, true, null)
                ))
        );
    }

    @Test
    void threeRunsStayInListOrderAndEveryRunRemainsWrapped() {
        assertEquals(
                "<color is=\"#696969\">root</color>"
                        + "<color is=\"#e8a93b\">gold</color>"
                        + "<color is=\"#696969\"><i>soft</i></color>",
                TooltipMarkup.wrap(List.of(
                        new TooltipMarkup.Run("root", false, false, null),
                        new TooltipMarkup.Run("gold", false, false, "#e8a93b"),
                        new TooltipMarkup.Run("soft", false, true, null)
                ))
        );
    }
}
