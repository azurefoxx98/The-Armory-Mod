package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.protocol.CalculationType;
import com.hypixel.hytale.protocol.ItemBase;
import com.hypixel.hytale.protocol.Modifier;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.protocol.packets.assets.UpdateItems;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import me.ladypaladra.thearmorymod.TheArmoryMod;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntPredicate;

/**
 * Makes an item's tooltip print a Multiplicative stat bonus as the bonus the player really gets.
 *
 * <p>For a stat the engine applies itself, a Multiplicative amount is the whole factor: the stat's
 * maximum is multiplied by it, so an Academy Hat's 1.25 gives 25 percent more mana. The client's
 * tooltip prints any Multiplicative amount as a bonus of the amount times 100, so the same hat reads
 * +125%. Item data cannot satisfy both readers, so this filter corrects what the client is told: each
 * player is sent the item list with those amounts lowered by 1, and the hat reads +25%. The server
 * keeps the authored amounts, so what a player gets does not change.</p>
 *
 * <p>The mod's own multiplier stats are left alone. {@link StatMultiplier} reads them as 1 plus the
 * amount, which is already what the tooltip prints.</p>
 *
 * <p>The engine caches each item's network form and shares it with every player, and an asset reload
 * sends one packet object to every connection. So no shared object is ever modified: an affected item
 * is replaced in the packet by a corrected copy, and a copy already made here is recognised and never
 * corrected twice.</p>
 */
public final class MultiplicativeTooltipFilter implements PacketFilter {

    private final List<LazyStatIndex> ownMultipliers = ArmoryStatIds.MULTIPLIERS.stream()
            .map(LazyStatIndex::new)
            .toList();

    /** Corrected copies made here. Weak, so a copy is forgotten once its packet has been sent. */
    private final Set<ItemBase> corrected = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    /** The count is logged once, at the first player's full item list, so a server owner can see it ran. */
    private final AtomicBoolean reported = new AtomicBoolean();

    @Override
    public boolean test(PacketHandler packetHandler, Packet packet) {
        if (packet instanceof UpdateItems update && update.items != null) {
            int replaced = correct(update.items, this::isOwnMultiplier, corrected);
            if (replaced > 0 && update.type == UpdateType.Init && reported.compareAndSet(false, true)) {
                TheArmoryMod.LOGGER.atInfo().log("Item tooltips: %d items have their Multiplicative stat bonus shown as the bonus players get", replaced);
            }
        }
        // Never cancels: the packet always goes out, corrected or as it was.
        return false;
    }

    private boolean isOwnMultiplier(int statIndex) {
        for (LazyStatIndex index : ownMultipliers) {
            if (index.get() == statIndex) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replaces every item whose tooltip would misprint a Multiplicative amount with a corrected copy.
     * Only existing keys are given new values, so the map's shape never changes under a reader.
     *
     * @param items the packet's items, by id
     * @param keepAsIs answers whether a stat index is read as 1 plus the amount, and so already prints right
     * @param alreadyCorrected copies made by an earlier call, skipped so a shared packet is corrected once
     * @return how many items were replaced
     */
    static int correct(@Nonnull Map<String, ItemBase> items,
                       @Nonnull IntPredicate keepAsIs,
                       @Nonnull Set<ItemBase> alreadyCorrected) {
        int replaced = 0;
        for (Map.Entry<String, ItemBase> entry : items.entrySet()) {
            ItemBase item = entry.getValue();
            if (item == null || !misprints(item, keepAsIs) || alreadyCorrected.contains(item)) {
                continue;
            }
            ItemBase copy = item.clone();
            if (copy.armor != null) lower(copy.armor.statModifiers, keepAsIs);
            if (copy.weapon != null) lower(copy.weapon.statModifiers, keepAsIs);
            if (copy.utility != null) lower(copy.utility.statModifiers, keepAsIs);
            alreadyCorrected.add(copy);
            entry.setValue(copy);
            replaced++;
        }
        return replaced;
    }

    static boolean misprints(@Nonnull ItemBase item, @Nonnull IntPredicate keepAsIs) {
        return (item.armor != null && misprints(item.armor.statModifiers, keepAsIs))
                || (item.weapon != null && misprints(item.weapon.statModifiers, keepAsIs))
                || (item.utility != null && misprints(item.utility.statModifiers, keepAsIs));
    }

    private static boolean misprints(@Nullable Map<Integer, Modifier[]> statModifiers, @Nonnull IntPredicate keepAsIs) {
        if (statModifiers == null) {
            return false;
        }
        for (Map.Entry<Integer, Modifier[]> entry : statModifiers.entrySet()) {
            if (entry.getKey() == null || keepAsIs.test(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            for (Modifier modifier : entry.getValue()) {
                if (modifier != null && modifier.calculationType == CalculationType.Multiplicative) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Works only on a copy's own arrays: ItemBase.clone copies every modifier. */
    private static void lower(@Nullable Map<Integer, Modifier[]> statModifiers, @Nonnull IntPredicate keepAsIs) {
        if (statModifiers == null) {
            return;
        }
        for (Map.Entry<Integer, Modifier[]> entry : statModifiers.entrySet()) {
            if (entry.getKey() == null || keepAsIs.test(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            for (Modifier modifier : entry.getValue()) {
                if (modifier != null && modifier.calculationType == CalculationType.Multiplicative) {
                    modifier.amount -= 1.0F;
                }
            }
        }
    }
}
