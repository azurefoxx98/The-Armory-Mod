package me.ladypaladra.thearmorymod.ui;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.util.MessageUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TooltipMessagesTest {

    @Test
    void aLegacyChildKeepsItsInheritedStyles() {
        Message source = Message.raw("root").bold(true).italic(true).color("#E8A93B")
                .insert(Message.raw("child"));
        var runs = TooltipMessages.flatten(source, (String) null);
        assertEquals(List.of(
                new TooltipMarkup.Run("root", true, true, "#E8A93B"),
                new TooltipMarkup.Run("child", true, true, "#E8A93B")
        ), runs);
    }

    @Test
    void explicitFalseStylesAndAChildColorOverrideTheParent() {
        Message source = Message.raw("root").bold(true).italic(true).color("#E8A93B")
                .insert(Message.raw("child").bold(false).italic(false).color("#ffffff"));
        assertEquals(new TooltipMarkup.Run("child", false, false, "#ffffff"),
                TooltipMessages.flatten(source, (String) null).get(1));
    }

    @Test
    void siblingStylesDoNotBleedThroughAnUnstyledParent() {
        Message source = Message.join(Message.raw("bold").bold(true), Message.raw("plain"));
        assertEquals(List.of(new TooltipMarkup.Run("bold", true, false, null),
                new TooltipMarkup.Run("plain", false, false, null)),
                TooltipMessages.flatten(source, (String) null));
    }

    @Test
    void nestedSealTranslationKeepsItsOrderAndColor() {
        Message source = Message.join(Message.raw("Words"),
                Message.raw("\n\n").insert(Message.translation("seal").color("#39f4f4")));
        var runs = TooltipMessages.flatten(source, node -> "seal".equals(node.messageId) ? "Sealed" : null);
        assertEquals(List.of(new TooltipMarkup.Run("Words", false, false, null),
                new TooltipMarkup.Run("\n\n", false, false, null),
                new TooltipMarkup.Run("Sealed", false, false, "#39f4f4")), runs);
    }

    @Test
    void aTranslatedRootDoesNotDiscardItsChildren() {
        Message source = Message.translation("base").insert(Message.raw(" tail"));
        assertEquals(List.of(new TooltipMarkup.Run("Base", false, false, null),
                new TooltipMarkup.Run(" tail", false, false, null)),
                TooltipMessages.flatten(source, "Base"));
    }

    @Test
    void anUnknownTranslationDoesNotDiscardOtherText() {
        Message source = Message.join(Message.translation("missing"), Message.raw("kept"));
        assertEquals(List.of(new TooltipMarkup.Run("kept", false, false, null)),
                TooltipMessages.flatten(source, node -> null));
    }

    @Test
    void aNullMessageHasNoRuns() {
        assertTrue(TooltipMessages.flatten(null, (String) null).isEmpty());
    }

    @Test
    void theStringEntryPointStillResolvesAStyledRoot() {
        assertEquals(List.of(new TooltipMarkup.Run("Translated", true, false, null)),
                TooltipMessages.flatten(Message.translation("base").bold(true), "Translated"));
    }

    @Test
    void aTranslationResolverCanApplyNativeTextParameters() {
        Message source = Message.translation("count").param("count", 5);
        var runs = TooltipMessages.flatten(source,
                node -> MessageUtil.formatText("Count {count}", node.params, node.messageParams));
        assertEquals(List.of(new TooltipMarkup.Run("Count 5", false, false, null)), runs);
    }

    @Test
    void theDepthGuardDoesNotModifyTheOriginalTree() {
        Message source = Message.empty();
        Message cursor = source;
        for (int i = 0; i < MessageTrees.MAX_DEPTH + 3; i++) {
            Message next = Message.raw(Integer.toString(i));
            cursor.insert(next);
            cursor = next;
        }
        var before = source.getFormattedMessage().clone();
        var runs = TooltipMessages.flatten(source, (String) null);
        assertEquals(MessageTrees.MAX_DEPTH, runs.size());
        assertEquals(Integer.toString(MessageTrees.MAX_DEPTH - 1), runs.getLast().text());
        assertEquals(before, source.getFormattedMessage());
    }

    @Test
    void deeplyNestedMessageParametersDoNotReachTheNativeFormatter() {
        Message parameter = Message.raw("tail");
        for (int i = 0; i < MessageTrees.MAX_DEPTH + 2; i++) {
            Message parent = Message.translation("nested");
            parent.getFormattedMessage().messageParams = Map.of("value", parameter.getFormattedMessage());
            parameter = parent;
        }
        int[] called = {0};
        var runs = TooltipMessages.flatten(parameter, node -> {
            called[0]++;
            return "unexpected";
        });
        assertTrue(runs.isEmpty());
        assertEquals(0, called[0]);
    }

    @Test
    void deepChildrenDoNotSuppressTheRootTranslation() {
        Message source = Message.translation("base");
        Message cursor = source;
        for (int i = 0; i < MessageTrees.MAX_DEPTH + 2; i++) {
            Message child = Message.raw("child");
            cursor.insert(child);
            cursor = child;
        }
        assertEquals("Base", TooltipMessages.flatten(source, node -> "Base").getFirst().text());
    }
}
