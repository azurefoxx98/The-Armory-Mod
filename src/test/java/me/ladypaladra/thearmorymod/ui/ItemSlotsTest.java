package me.ladypaladra.thearmorymod.ui;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemSlotsTest {

    @Test
    void inheritedQualityIsNotTurnedIntoAnOverride() {
        BsonDocument slot = clientSlot("{\"Id\":\"Armor_Iron_Head\",\"Quantity\":1}");
        BsonDocument stack = slot.getDocument("ItemStack");
        assertFalse(stack.containsKey("Quality"));
        assertFalse(stack.containsKey("QualityOverride"));
    }

    @Test
    void explicitQualityUsesTheClientField() {
        BsonDocument slot = clientSlot("{\"Id\":\"Armor_Iron_Head\",\"QualityOverride\":8}");
        BsonDocument stack = slot.getDocument("ItemStack");
        assertEquals(8, stack.getInt32("Quality").getValue());
        assertFalse(stack.containsKey("QualityOverride"));
    }

    @Test
    void qualityAtIndexZeroRemainsExplicit() {
        BsonDocument slot = clientSlot("{\"Id\":\"Armor_Iron_Head\",\"QualityOverride\":0}");
        assertEquals(0, slot.getDocument("ItemStack").getInt32("Quality").getValue());
    }

    @Test
    void negativePacketSentinelIsNotSentAsAQualityIndex() {
        BsonDocument slot = clientSlot("{\"Id\":\"Armor_Iron_Head\",\"QualityOverride\":-1}");
        BsonDocument stack = slot.getDocument("ItemStack");
        assertFalse(stack.containsKey("Quality"));
        assertFalse(stack.containsKey("QualityOverride"));
    }

    @Test
    void metadataIsRemovedWithoutChangingTheSourceOrNativeSlotFields() {
        BsonDocument original = BsonDocument.parse("""
                {"0":[{"ItemStack":{"Id":"Armor_Iron_Head","Quantity":2,
                  "Durability":25.0,"MaxDurability":100.0,"QualityOverride":8,
                  "OverrideDroppedItemAnimation":true,"Metadata":{"ForeignMod":{"keep":true}}},
                  "Description":"<b>kept</b>","IsActivatable":true,"IsItemIncompatible":false}]}
                """);
        BsonDocument converted = BsonDocument.parse(ItemSlots.clientData(original.toJson()));
        BsonDocument slot = converted.getArray("0").get(0).asDocument();
        BsonDocument stack = slot.getDocument("ItemStack");
        assertFalse(stack.containsKey("Metadata"));
        assertEquals("Armor_Iron_Head", stack.getString("Id").getValue());
        assertEquals(2, stack.getInt32("Quantity").getValue());
        assertEquals(25.0, stack.getDouble("Durability").getValue());
        assertEquals(100.0, stack.getDouble("MaxDurability").getValue());
        assertTrue(stack.getBoolean("OverrideDroppedItemAnimation").getValue());
        assertEquals("<b>kept</b>", slot.getString("Description").getValue());
        assertTrue(slot.getBoolean("IsActivatable").getValue());
        assertFalse(slot.getBoolean("IsItemIncompatible").getValue());
        assertTrue(original.getArray("0").get(0).asDocument().getDocument("ItemStack")
                .getDocument("Metadata").getDocument("ForeignMod").getBoolean("keep").getValue());
    }

    @Test
    void emptyGridRemainsEmpty() {
        assertTrue(BsonDocument.parse(ItemSlots.clientData("{\"0\":[]}")).getArray("0").isEmpty());
    }

    @Test
    void multipleSlotsKeepIndependentQualities() {
        BsonDocument converted = BsonDocument.parse(ItemSlots.clientData("""
                {"0":[{"ItemStack":{"Id":"first","Quantity":1}},
                  {"ItemStack":{"Id":"second","Quantity":3,"QualityOverride":8},
                   "IsItemIncompatible":true}]}
                """));
        assertEquals(2, converted.getArray("0").size());
        assertFalse(converted.getArray("0").get(0).asDocument().getDocument("ItemStack").containsKey("Quality"));
        BsonDocument second = converted.getArray("0").get(1).asDocument();
        assertEquals(8, second.getDocument("ItemStack").getInt32("Quality").getValue());
        assertEquals(3, second.getDocument("ItemStack").getInt32("Quantity").getValue());
        assertTrue(second.getBoolean("IsItemIncompatible").getValue());
    }

    @Test
    void emptySlotWithoutAStackRemainsValid() {
        BsonDocument converted = BsonDocument.parse(ItemSlots.clientData("{\"0\":[{\"IsActivatable\":false}]}"));
        assertFalse(converted.getArray("0").get(0).asDocument().containsKey("ItemStack"));
    }

    private static BsonDocument clientSlot(String stack) {
        return BsonDocument.parse(ItemSlots.clientData("{\"0\":[{\"ItemStack\":" + stack + "}]}"))
                .getArray("0").get(0).asDocument();
    }
}
