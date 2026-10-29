package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.protocol.CalculationType;
import com.hypixel.hytale.protocol.ItemArmor;
import com.hypixel.hytale.protocol.ItemBase;
import com.hypixel.hytale.protocol.ItemUtility;
import com.hypixel.hytale.protocol.ItemWeapon;
import com.hypixel.hytale.protocol.Modifier;
import com.hypixel.hytale.protocol.ModifierTarget;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class MultiplicativeTooltipFilterTest {

    private static final float DELTA = 0.00001F;
    private static final int HEALTH = 0;
    private static final int MANA = 3;
    private static final int OWN_MOVE_SPEED = 20;
    private static final IntPredicate OWN = index -> index == OWN_MOVE_SPEED;

    private static Modifier modifier(CalculationType type, float amount) {
        return new Modifier(ModifierTarget.Max, type, amount);
    }

    private static Map<Integer, Modifier[]> stats(int stat, Modifier... modifiers) {
        Map<Integer, Modifier[]> map = new HashMap<>();
        map.put(stat, modifiers);
        return map;
    }

    private static ItemBase academyHat() {
        ItemBase item = new ItemBase();
        item.id = "AcademyHat";
        item.armor = new ItemArmor();
        item.armor.statModifiers = new HashMap<>();
        item.armor.statModifiers.put(MANA, new Modifier[] {modifier(CalculationType.Multiplicative, 1.25F)});
        item.armor.statModifiers.put(HEALTH, new Modifier[] {modifier(CalculationType.Additive, 14.0F)});
        return item;
    }

    private static Set<ItemBase> fresh() {
        return Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    }

    @Test
    void academyHatReadsItsRealBonusAndKeepsItsAdditiveHealth() {
        ItemBase hat = academyHat();
        Map<String, ItemBase> items = new HashMap<>(Map.of("AcademyHat", hat));

        assertEquals(1, MultiplicativeTooltipFilter.correct(items, OWN, fresh()));

        ItemBase sent = items.get("AcademyHat");
        assertEquals(0.25F, sent.armor.statModifiers.get(MANA)[0].amount, DELTA);
        assertEquals(14.0F, sent.armor.statModifiers.get(HEALTH)[0].amount, DELTA);
    }

    @Test
    void theSharedCachedItemIsNeverChanged() {
        ItemBase hat = academyHat();
        Map<String, ItemBase> items = new HashMap<>(Map.of("AcademyHat", hat));

        MultiplicativeTooltipFilter.correct(items, OWN, fresh());

        assertNotSame(hat, items.get("AcademyHat"));
        assertEquals(1.25F, hat.armor.statModifiers.get(MANA)[0].amount, DELTA);
    }

    @Test
    void onePacketSentToTwoPlayersIsCorrectedOnce() {
        Map<String, ItemBase> items = new HashMap<>(Map.of("AcademyHat", academyHat()));
        Set<ItemBase> made = fresh();

        MultiplicativeTooltipFilter.correct(items, OWN, made);
        ItemBase afterFirst = items.get("AcademyHat");
        assertEquals(0, MultiplicativeTooltipFilter.correct(items, OWN, made));

        assertSame(afterFirst, items.get("AcademyHat"));
        assertEquals(0.25F, items.get("AcademyHat").armor.statModifiers.get(MANA)[0].amount, DELTA);
    }

    @Test
    void theModsOwnStatsAlreadyPrintRightAndAreLeftAlone() {
        ItemBase rig = new ItemBase();
        rig.armor = new ItemArmor();
        rig.armor.statModifiers = stats(OWN_MOVE_SPEED, modifier(CalculationType.Multiplicative, 0.2F));
        Map<String, ItemBase> items = new HashMap<>(Map.of("EngineeringRig", rig));

        assertEquals(0, MultiplicativeTooltipFilter.correct(items, OWN, fresh()));
        assertSame(rig, items.get("EngineeringRig"));
    }

    @Test
    void heldWeaponsAndUtilityItemsAreCorrectedToo() {
        ItemBase blade = new ItemBase();
        blade.weapon = new ItemWeapon();
        blade.weapon.statModifiers = stats(HEALTH, modifier(CalculationType.Multiplicative, 1.5F));
        ItemBase charm = new ItemBase();
        charm.utility = new ItemUtility();
        charm.utility.statModifiers = stats(MANA, modifier(CalculationType.Multiplicative, 1.1F));
        Map<String, ItemBase> items = new HashMap<>(Map.of("LahatChereb", blade, "Charm", charm));

        assertEquals(2, MultiplicativeTooltipFilter.correct(items, OWN, fresh()));
        assertEquals(0.5F, items.get("LahatChereb").weapon.statModifiers.get(HEALTH)[0].amount, DELTA);
        assertEquals(0.1F, items.get("Charm").utility.statModifiers.get(MANA)[0].amount, DELTA);
    }

    @Test
    void hardmodeMaskReadsAsTheLossItGives() {
        ItemBase mask = new ItemBase();
        mask.armor = new ItemArmor();
        mask.armor.statModifiers = stats(HEALTH, modifier(CalculationType.Multiplicative, 0.01F));
        Map<String, ItemBase> items = new HashMap<>(Map.of("Hardmode_Mask_Goblin", mask));

        MultiplicativeTooltipFilter.correct(items, OWN, fresh());

        assertEquals(-0.99F, items.get("Hardmode_Mask_Goblin").armor.statModifiers.get(HEALTH)[0].amount, DELTA);
    }

    @Test
    void itemsWithOnlyAdditiveStatsAreNotCopied() {
        ItemBase plain = new ItemBase();
        plain.armor = new ItemArmor();
        plain.armor.statModifiers = stats(HEALTH, modifier(CalculationType.Additive, 20.0F));
        Map<String, ItemBase> items = new HashMap<>(Map.of("Plain", plain));

        assertEquals(0, MultiplicativeTooltipFilter.correct(items, OWN, fresh()));
        assertSame(plain, items.get("Plain"));
        assertFalse(MultiplicativeTooltipFilter.misprints(plain, OWN));
    }
}
