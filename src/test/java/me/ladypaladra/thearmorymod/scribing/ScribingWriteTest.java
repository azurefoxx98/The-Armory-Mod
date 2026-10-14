package me.ladypaladra.thearmorymod.scribing;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScribingWriteTest {

    private static Message message(String markup) {
        return ScribingWrite.toMessage(ScribingMarkup.parse(markup).runs());
    }

    @Test
    void sealingTheJustInscribedStyledDraftDoesNotChargeInk() {
        Message name = message("Background Rook");
        Message description = message("<b>Silent</b> test helm.");
        var display = new ItemDisplayMetadata(name, description);

        var draft = ScribingWrite.prepareSeal(display, "Background Rook", "<b>Silent</b> test helm.",
                message("Background Rook"), message("<b>Silent</b> test helm."));

        assertFalse(draft.textChanges());
        assertSame(name, draft.name());
        assertSame(description, draft.description());
    }

    @Test
    void unchangedReopenedFieldsKeepTheExactStoredMessageTree() {
        Message stored = message("<b>Silent</b> test helm.");
        String refilled = "<b>Silent</b><b> test helm.</b>";

        var draft = ScribingWrite.prepareSeal(new ItemDisplayMetadata(null, stored), "", refilled,
                null, message(refilled));

        assertFalse(draft.textChanges());
        assertSame(stored, draft.description());
    }

    @Test
    void changingTheNameStillRequiresInk() {
        Message edited = message("Dawn");
        var draft = ScribingWrite.prepareSeal(new ItemDisplayMetadata(message("Dusk"), null),
                "Dawn", "", edited, null);

        assertTrue(draft.textChanges());
        assertSame(edited, draft.name());
    }

    @Test
    void changingOnlyTheDescriptionStillRequiresInk() {
        Message edited = message("A new story.");
        var draft = ScribingWrite.prepareSeal(new ItemDisplayMetadata(null, message("An old story.")),
                "", "A new story.", null, edited);

        assertTrue(draft.textChanges());
        assertSame(edited, draft.description());
    }

    @Test
    void changingOnlyFormattingStillRequiresInk() {
        var draft = ScribingWrite.prepareSeal(new ItemDisplayMetadata(null, message("Silent")),
                "", "<b>Silent</b>", null, message("<b>Silent</b>"));

        assertTrue(draft.textChanges());
    }

    @Test
    void clearingAnExistingFieldIsAWrite() {
        var draft = ScribingWrite.prepareSeal(new ItemDisplayMetadata(message("Dawn"), null),
                "", "", null, null);

        assertTrue(draft.textChanges());
        assertNull(draft.name());
    }

    @Test
    void absentDisplayAndBlankFieldsDoNotInventAWrite() {
        var draft = ScribingWrite.prepareSeal(null, "", "", null, null);

        assertFalse(draft.textChanges());
        assertNull(draft.name());
        assertNull(draft.description());
    }

    @Test
    void anUneditableTranslationIsNotTheSameAsAnEmptyDraft() {
        var display = new ItemDisplayMetadata(Message.translation("another.mod.name"), null);
        var draft = ScribingWrite.prepareSeal(display, "", "", null, null);

        assertTrue(draft.textChanges());
        assertNull(draft.name());
    }
}
