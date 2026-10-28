package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The charge as the hand shows it. While a die's throw is held, the player's stat {@value #STAT} climbs from 0 to 100
 * with the throw's own strength, and the die in the hand follows it: the item's appearance conditions light its marks
 * brighter and gather motes round it, step by step (the dice item files). At 100 the die's full look bursts once in its
 * colour and the server plays a chime. Releasing or cancelling puts the stat back to 0.
 *
 * <p>The stat is shared, so every player near sees the glow, and hidden from the item tooltip. Nothing reads it but the
 * dice's own appearance: it changes no play.</p>
 */
final class DiceCharge {

    static final String STAT = "Armory_Dice_Charge";
    static final float FULL = 100.0f;

    static final String SOUND_FULL = "SFX_Crystal_Hit";
    static final String SOUND_FULL_SHIMMER = "SFX_MemoryMote";

    /**
     * How long, in nanoseconds, a raised stat may go without a held tick before it is put back: the charge ended some
     * way its own finish step never saw. A held charge refreshes it every tick, a twentieth of this.
     */
    static final long STALE_NANOS = 400_000_000L;

    /**
     * When each player last held a charge, for the stale check and for the idle fidgets, which wait after a throw.
     */
    private static final Map<UUID, Long> LAST_HELD = new ConcurrentHashMap<>();

    private static int statIndex = Integer.MIN_VALUE;

    private DiceCharge() {
    }

    /**
     * The stat's value for a hold of {@code seconds}: the throw's strength, so the light grows exactly as the throw
     * does, starting gently and climbing faster.
     */
    static float value(double seconds) {
        return (float) Math.round(ThrowFeel.power(seconds) * FULL);
    }

    static void held(@Nonnull InteractionContext context, float seconds) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        if (commandBuffer == null) {
            return;
        }
        Ref<EntityStore> ref = context.getEntity();
        PlayerRef player = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (player != null) {
            LAST_HELD.put(player.getUuid(), System.nanoTime());
        }
        float now = value(seconds);
        float before = set(ref, now, commandBuffer);
        if (before < FULL && now >= FULL) {
            flash(ref, context.getHeldItem(), commandBuffer);
        }
    }

    static void ended(@Nonnull InteractionContext context) {
        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        if (commandBuffer != null) {
            set(context.getEntity(), 0.0f, commandBuffer);
        }
    }

    /**
     * Seconds since the player last held a dice charge, or a very large number if never.
     */
    static double secondsSinceHeld(@Nonnull UUID player) {
        Long at = LAST_HELD.get(player);
        return at == null ? Double.MAX_VALUE : (System.nanoTime() - at) / 1.0E9;
    }

    /**
     * Sets the player's charge stat, only when it changes, so a full charge held for a while sends nothing more.
     *
     * @return the value it had before, or {@link #FULL} when the player has no such stat
     */
    private static float set(@Nonnull Ref<EntityStore> ref, float value,
                             @Nonnull ComponentAccessor<EntityStore> accessor) {
        int index = index();
        EntityStatMap stats = ref.isValid() ? accessor.getComponent(ref, EntityStatMap.getComponentType()) : null;
        EntityStatValue current = stats != null && index >= 0 ? stats.get(index) : null;
        if (current == null) {
            return FULL;
        }
        float before = current.get();
        if (before != value) {
            stats.setStatValue(index, value);
        }
        return before;
    }

    /**
     * The chime at full strength, with a shimmer under it, heard by everyone near. The flash itself is part of the die's
     * full look (the dice item files), so it bursts on the die wherever the wind-up has taken the hand: a burst placed
     * here could only guess where the hand is, and the guess showed beside the player.
     */
    private static void flash(@Nonnull Ref<EntityStore> ref, @Nullable ItemStack held,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (held == null || DieType.byItemId(held.getItemId()) == null) {
            return;
        }
        Vector3d hand = hand(ref, commandBuffer);
        DiceShow.sound(SOUND_FULL, hand, 0.55f, 1.7f, commandBuffer);
        DiceShow.sound(SOUND_FULL_SHIMMER, hand, 0.45f, 1.5f, commandBuffer);
    }

    /**
     * Where the right hand holds the die, near enough for an effect: a little forward of the eye, to its right and
     * below it.
     */
    @Nonnull
    static Vector3d hand(@Nonnull Ref<EntityStore> ref, @Nonnull ComponentAccessor<EntityStore> accessor) {
        Transform look = TargetUtil.getLook(ref, accessor);
        Vector3d eye = new Vector3d(look.getPosition());
        Vector3d aim = new Vector3d(look.getDirection()).normalize();
        Vector3d right = new Vector3d(-aim.z, 0.0, aim.x);
        if (right.lengthSquared() < 1.0E-8) {
            right.set(1.0, 0.0, 0.0);
        }
        right.normalize();
        return eye.fma(0.55, aim).fma(0.3, right).add(0.0, -0.35, 0.0);
    }

    private static int index() {
        // Looked up until found: the stat is a mod asset, loaded after the plugin starts.
        if (statIndex < 0) {
            int found = EntityStatType.getAssetMap().getIndex(STAT);
            statIndex = found <= EntityStatType.UNKNOWN_ID ? -1 : found;
        }
        return statIndex;
    }

    /**
     * Puts a player's raised charge stat back when no charge has held it for a moment. Covers any end the charge's own
     * finish step misses, so a die can never keep glowing in a hand that stopped throwing.
     */
    public static final class Stale extends EntityTickingSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Query.and(PlayerRef.getComponentType(), EntityStatMap.getComponentType());
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                         @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            int stat = index();
            EntityStatMap stats = chunk.getComponent(index, EntityStatMap.getComponentType());
            PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
            EntityStatValue value = stats != null && stat >= 0 ? stats.get(stat) : null;
            if (value == null || player == null || value.get() <= 0.0f) {
                return;
            }
            Long at = LAST_HELD.get(player.getUuid());
            if (at == null || System.nanoTime() - at > STALE_NANOS) {
                stats.setStatValue(stat, 0.0f);
            }
        }
    }

    /**
     * A player coming into the world starts dark: the stat is saved with the player, and one who left mid-charge
     * would otherwise come back with a glowing die.
     */
    public static final class Joined extends RefSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Query.and(PlayerRef.getComponentType(), EntityStatMap.getComponentType());
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                                  @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            set(ref, 0.0f, commandBuffer);
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                                   @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player != null) {
                LAST_HELD.remove(player.getUuid());
            }
        }
    }
}
