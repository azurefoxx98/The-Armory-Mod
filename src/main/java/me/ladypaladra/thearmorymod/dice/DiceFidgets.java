package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.ItemAnimation;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.asset.common.BlockyAnimationCache;
import com.hypixel.hytale.server.core.asset.type.itemanimation.config.ItemPlayerAnimations;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The die's life in an idle hand. Standing still with a die, now and then a player tosses it, weighs it, spins it on
 * the palm, lifts it to read it, rolls it over the back of the hand or tosses it to the other hand and back (the
 * D100's pair has gestures of its own), seen by everyone near, the holder too. When is {@link FidgetClock}'s.
 *
 * <p>The fidgets are the held item's animation set's entries named {@value #PREFIX}, and how long each plays is read
 * from its clip, the way the engine times an interaction's animation. Each die turns about its own middle because its
 * held model turns on a node pivoted there: the clips carry no number about a die.</p>
 */
public final class DiceFidgets extends EntityTickingSystem<EntityStore> {

    static final String PREFIX = "Fidget_";

    private static final Map<UUID, FidgetClock> CLOCKS = new ConcurrentHashMap<>();
    private static final Map<String, List<FidgetClock.Fidget>> SETS = new ConcurrentHashMap<>();
    private static final Set<UUID> SHOWCASE = ConcurrentHashMap.newKeySet();

    /**
     * Turns the showcase on or off for one player: every fidget in turn, a second apart.
     *
     * @return whether it is now on
     */
    static boolean toggleShowcase(@Nonnull UUID player) {
        if (SHOWCASE.remove(player)) {
            return false;
        }
        SHOWCASE.add(player);
        return true;
    }

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
        ItemStack held = InventoryComponent.getItemInHand(commandBuffer, ref);
        boolean die = held != null && DieType.byItemId(held.getItemId()) != null;
        UUID id = player.getUuid();
        FidgetClock clock = CLOCKS.get(id);
        if (!die && clock == null) {
            return;
        }
        if (clock == null) {
            clock = new FidgetClock();
            CLOCKS.put(id, clock);
        }
        clock.showcase(SHOWCASE.contains(id));
        String set = die ? held.getItem().getPlayerAnimationsId() : null;
        List<FidgetClock.Fidget> fidgets = set == null ? List.of() : fidgets(set);
        InteractionManager interactions = commandBuffer.getComponent(ref,
                InteractionModule.get().getInteractionManagerComponent());
        boolean free = interactions == null || interactions.getChains().isEmpty();
        // The off hand holds what its active utility slot holds, and the toss to the left hand needs it empty.
        InventoryComponent.Utility utility = commandBuffer.getComponent(ref,
                InventoryComponent.Utility.getComponentType());
        boolean offHand = utility == null || ItemStack.isEmpty(utility.getActiveItem());
        FidgetClock.Act act = clock.tick(System.nanoTime(), die ? held.getItemId() : null, fidgets,
                still(movement.getMovementStates()), free, offHand, ThreadLocalRandom.current());
        switch (act) {
            case PLAY -> AnimationUtils.playAnimation(ref, AnimationSlot.Action, set, clock.chosen(), true,
                    commandBuffer);
            case STOP -> AnimationUtils.stopAnimation(ref, AnimationSlot.Action, true, commandBuffer);
            case NONE -> {
                if (!die) {
                    CLOCKS.remove(id);
                }
            }
        }
    }

    /**
     * Standing still on the ground, doing nothing a fidget would look wrong over.
     */
    static boolean still(@Nonnull MovementStates s) {
        return s.idle && s.onGround && !s.crouching && !s.forcedCrouching && !s.jumping && !s.falling && !s.flying
                && !s.climbing && !s.inFluid && !s.swimming && !s.mantling && !s.sliding && !s.mounting && !s.rolling
                && !s.sitting && !s.gliding && !s.sleeping;
    }

    /**
     * The fidgets of an animation set and how long each plays, in seconds at its entry's speed. Read once a set's
     * clips are all loaded. A set whose clips are not loaded yet has none until they are.
     */
    @Nonnull
    static List<FidgetClock.Fidget> fidgets(@Nonnull String setId) {
        List<FidgetClock.Fidget> known = SETS.get(setId);
        if (known != null) {
            return known;
        }
        ItemPlayerAnimations animations = ItemPlayerAnimations.getAssetMap().getAsset(setId);
        if (animations == null) {
            return List.of();
        }
        List<FidgetClock.Fidget> out = new ArrayList<>();
        for (Map.Entry<String, ItemAnimation> entry : animations.getAnimations().entrySet()) {
            if (!entry.getKey().startsWith(PREFIX)) {
                continue;
            }
            Double seconds = seconds(entry.getValue());
            if (seconds == null) {
                return List.of();
            }
            out.add(new FidgetClock.Fidget(entry.getKey(), seconds));
        }
        out.sort(Comparator.comparing(FidgetClock.Fidget::name));
        List<FidgetClock.Fidget> found = List.copyOf(out);
        SETS.put(setId, found);
        return found;
    }

    /**
     * How long an entry's clip plays: its length over its speed. The third-person clip is the one everyone near sees,
     * and the recipes make both views the same length.
     */
    @Nullable
    private static Double seconds(@Nonnull ItemAnimation entry) {
        if (entry.thirdPerson == null || entry.speed <= 0) {
            return null;
        }
        BlockyAnimationCache.BlockyAnimation clip = BlockyAnimationCache.getNow(entry.thirdPerson);
        return clip == null ? null : clip.getDurationSeconds() / entry.speed;
    }

    /**
     * Forgets a player who leaves the world.
     */
    public static final class Left extends RefSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Query.and(PlayerRef.getComponentType(), MovementStatesComponent.getComponentType());
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                                  @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                                   @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            if (player != null) {
                CLOCKS.remove(player.getUuid());
                SHOWCASE.remove(player.getUuid());
            }
        }
    }
}
