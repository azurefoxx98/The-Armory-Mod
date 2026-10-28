package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * The die's life in an idle hand. Holding a die and standing still, a player now and then rolls it over the knuckles
 * and back, or tosses it up and catches it, seen by everyone near. The die's own idle keeps it rocking in the palm
 * between.
 *
 * <p>A fidget waits for a calm hand: standing still with a die for a few seconds, nothing else being done with the
 * hand, and no throw just made. So it never cuts into a throw, a swing, a meal or a chest.</p>
 */
public final class DiceFidgets extends EntityTickingSystem<EntityStore> {

    /**
     * The dice's animation set, and the two fidgets in it.
     */
    static final String ANIMATIONS = "Armory_Dice";
    static final String ROLL = "FidgetRoll";
    static final String TOSS = "FidgetToss";

    /**
     * Seconds of a calm hand before any fidget, and the random spread between two.
     */
    static final double CALM = 3.0;
    static final double MIN_GAP = 7.0;
    static final double MAX_GAP = 16.0;

    /**
     * Of the fidgets, how many are rolls: the toss is the bigger gesture, so it comes less often.
     */
    static final double ROLL_SHARE = 0.6;

    private final Map<UUID, Long> due = new ConcurrentHashMap<>();

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(PlayerRef.getComponentType(), MovementStatesComponent.getComponentType());
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
        MovementStatesComponent movement = chunk.getComponent(index, MovementStatesComponent.getComponentType());
        if (player == null || movement == null) {
            return;
        }
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        UUID id = player.getUuid();
        long now = System.nanoTime();
        if (!calm(ref, movement.getMovementStates(), id, commandBuffer)) {
            // Restless: the next fidget can come no sooner than a calm spell from now.
            due.merge(id, now + seconds(CALM), Math::max);
            if (!holdsDie(ref, commandBuffer)) {
                due.remove(id);
            }
            return;
        }
        RandomGenerator rng = ThreadLocalRandom.current();
        Long at = due.get(id);
        if (at == null) {
            due.put(id, now + seconds(CALM + gap(rng) * 0.5));
            return;
        }
        if (now < at) {
            return;
        }
        due.put(id, now + seconds(gap(rng)));
        AnimationUtils.playAnimation(ref, AnimationSlot.Action, ANIMATIONS, pick(rng), true, commandBuffer);
    }

    /**
     * Whether the hand is free to fidget: a die held, standing still, nothing else running, no throw just made.
     */
    private static boolean calm(@Nonnull Ref<EntityStore> ref, @Nonnull MovementStates states, @Nonnull UUID player,
                                @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (!states.idle || states.crouching || states.jumping || states.falling || states.swimming || states.climbing) {
            return false;
        }
        if (!holdsDie(ref, commandBuffer) || DiceCharge.secondsSinceHeld(player) < CALM) {
            return false;
        }
        InteractionManager interactions = commandBuffer.getComponent(ref,
                InteractionModule.get().getInteractionManagerComponent());
        return interactions == null || interactions.getChains().isEmpty();
    }

    private static boolean holdsDie(@Nonnull Ref<EntityStore> ref, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        ItemStack held = InventoryComponent.getItemInHand(commandBuffer, ref);
        return held != null && DieType.byItemId(held.getItemId()) != null;
    }

    /**
     * Seconds until the next fidget, evenly between the shortest and longest gap.
     */
    static double gap(@Nonnull RandomGenerator rng) {
        return MIN_GAP + (MAX_GAP - MIN_GAP) * rng.nextDouble();
    }

    @Nonnull
    static String pick(@Nonnull RandomGenerator rng) {
        return rng.nextDouble() < ROLL_SHARE ? ROLL : TOSS;
    }

    private static long seconds(double s) {
        return (long) (s * 1.0E9);
    }
}
