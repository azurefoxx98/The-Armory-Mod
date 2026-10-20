package me.ladypaladra.thearmorymod.powers;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.stats.ArmoryStatIds;
import me.ladypaladra.thearmorymod.stats.LazyStatIndex;
import me.ladypaladra.thearmorymod.stats.StatAddition;
import me.ladypaladra.thearmorymod.stats.StatMultiplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.LocalDateTime;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Gives a player more mana or health while it is night, from gear carrying a night stat.
 *
 * <p>A piece declares its night amount as an Additive armour modifier on {@code TA_Night_Mana} or
 * {@code TA_Night_Health}, so the engine sums every worn piece and prints the amount on the
 * tooltip. While the world clock is at night this system adds that sum to the player's maximum
 * mana or health under its own modifier key, and takes it away again at dawn or when the piece
 * comes off. The engine's armour recalculation only replaces modifiers under its own armour key,
 * so the two never overwrite each other.</p>
 *
 * <p>Night is the engine's own: the clock spends 60 percent of the day in daylight, from
 * {@link WorldTimeResource#SUNRISE_SECONDS} for {@link WorldTimeResource#DAYTIME_SECONDS}, which
 * is one second short of 04:48 to 19:12. The world clock advances through exactly that split, so
 * the bonus begins as the sky darkens.</p>
 *
 * <p>When the maximum drops at dawn the engine clamps the current value to the new maximum, the
 * same thing that happens when a player takes off armour that gave health.</p>
 *
 * <p>Like every stat modifier, this one is saved with the player. While the mod is installed that
 * changes nothing, because the first tick after joining corrects it. If a server removes the mod,
 * a player last saved at night in such gear keeps that night amount.</p>
 */
public final class NightBonusSystem extends EntityTickingSystem<EntityStore> {

    static final String MODIFIER_KEY = "TheArmory_Night";

    /** A night stat carried by gear, and the player stat whose maximum it raises at night. */
    private record NightBonus(LazyStatIndex carried, IntSupplier raised) {
    }

    private final List<NightBonus> bonuses = List.of(
            new NightBonus(new LazyStatIndex(ArmoryStatIds.NIGHT_MANA), DefaultEntityStatTypes::getMana),
            new NightBonus(new LazyStatIndex(ArmoryStatIds.NIGHT_HEALTH), DefaultEntityStatTypes::getHealth)
    );

    private final Query<EntityStore> query = Archetype.of(
            PlayerRef.getComponentType(),
            EntityStatMap.getComponentType()
    );

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> chunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        EntityStatMap statMap = chunk.getComponent(index, EntityStatMap.getComponentType());
        if (statMap == null) {
            return;
        }

        boolean night = isNight(store.getResource(WorldTimeResource.getResourceType()));
        for (NightBonus bonus : bonuses) {
            float amount = night ? StatAddition.forStat(statMap, bonus.carried().get()) : 0.0F;
            apply(statMap, bonus.raised().getAsInt(), amount);
        }
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        // This writes stat modifiers, which queue network updates, so it stays on the world thread.
        return false;
    }

    static boolean isNight(@Nullable WorldTimeResource time) {
        LocalDateTime clock = time != null ? time.getGameDateTime() : null;
        // A clock that has not been set yet reads as day, so no bonus appears from nothing.
        return clock != null && isNightSecond(clock.get(ChronoField.SECOND_OF_DAY));
    }

    static boolean isNightSecond(int secondOfDay) {
        return secondOfDay < WorldTimeResource.SUNRISE_SECONDS
                || secondOfDay >= WorldTimeResource.SUNRISE_SECONDS + WorldTimeResource.DAYTIME_SECONDS;
    }

    /**
     * Brings the night modifier on one stat to the wanted amount, touching the stat only when it
     * changes. An amount of 0 leaves no modifier at all, so a player who never wears night gear
     * carries nothing extra in their save.
     */
    private static void apply(@Nonnull EntityStatMap statMap, int statIndex, float amount) {
        // removeModifier logs a warning for a stat the entity does not hold.
        if (statIndex == AssetMapWithIndexes.NOT_FOUND || statMap.get(statIndex) == null) {
            return;
        }

        Modifier existing = statMap.getModifier(statIndex, MODIFIER_KEY);
        if (amount == 0.0F) {
            if (existing != null) {
                statMap.removeModifier(statIndex, MODIFIER_KEY);
            }
            return;
        }

        if (existing instanceof StaticModifier current
                && current.getCalculationType() == StaticModifier.CalculationType.ADDITIVE
                && current.getTarget() == Modifier.ModifierTarget.MAX
                && Math.abs(current.getAmount() - amount) <= StatMultiplier.EPSILON) {
            return;
        }

        statMap.putModifier(statIndex, MODIFIER_KEY, new StaticModifier(
                Modifier.ModifierTarget.MAX, StaticModifier.CalculationType.ADDITIVE, amount));
    }
}
