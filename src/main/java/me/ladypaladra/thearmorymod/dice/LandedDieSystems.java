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
import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.server.core.modules.entity.component.DynamicLight;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Landed dice as the world loads and unloads them.
 */
public final class LandedDieSystems {

    /**
     * Each loaded D100 die by its pair, so picking up one finds the other.
     */
    private static final Map<UUID, Map<Ref<EntityStore>, Boolean>> PAIRS = new ConcurrentHashMap<>();

    private LandedDieSystems() {
    }

    /**
     * The other die of {@code pair} that is loaded, other than {@code self}, or null.
     */
    @Nullable
    static Ref<EntityStore> partner(@Nonnull UUID pair, @Nonnull Ref<EntityStore> self) {
        Map<Ref<EntityStore>, Boolean> dice = PAIRS.get(pair);
        if (dice == null) {
            return null;
        }
        for (Ref<EntityStore> ref : dice.keySet()) {
            if (!ref.equals(self) && ref.isValid()) {
                return ref;
            }
        }
        return null;
    }

    /**
     * Keeps the pair index, and clears a die whose pair was taken back while this die's chunk was unloaded.
     */
    public static final class Loaded extends RefSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return DiceModule.landedType();
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                                  @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            LandedDie die = commandBuffer.getComponent(ref, DiceModule.landedType());
            UUID pair = die != null ? die.pair() : null;
            if (pair == null) {
                return;
            }
            if (DiceModule.ledger().consumeCollected(pair)) {
                commandBuffer.removeEntity(ref, RemoveReason.REMOVE);
                return;
            }
            PAIRS.computeIfAbsent(pair, k -> new ConcurrentHashMap<>()).put(ref, Boolean.TRUE);
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                                   @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            LandedDie die = store.getComponent(ref, DiceModule.landedType());
            UUID pair = die != null ? die.pair() : null;
            if (pair == null) {
                return;
            }
            PAIRS.computeIfPresent(pair, (k, dice) -> {
                dice.remove(ref);
                return dice.isEmpty() ? null : dice;
            });
        }
    }

    /**
     * Fades each freshly landed die's light, then takes the light away.
     */
    public static final class Glow extends EntityTickingSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return DiceModule.glowType();
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                         @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            DieGlow glow = chunk.getComponent(index, DiceModule.glowType());
            if (glow == null) {
                return;
            }
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            double b = glow.brightness();
            glow.age++;
            DynamicLight light = chunk.getComponent(index, DynamicLight.getComponentType());
            if (b <= 0.0) {
                commandBuffer.removeComponent(ref, DiceModule.glowType());
                if (light != null) {
                    commandBuffer.removeComponent(ref, DynamicLight.getComponentType());
                }
                return;
            }
            ColorLight colour = light(glow.colour, b * Math.min(1.5, glow.strength));
            if (light == null) {
                commandBuffer.addComponent(ref, DynamicLight.getComponentType(), new DynamicLight(colour));
            } else {
                light.setColorLight(colour);
            }
        }
    }

    /**
     * The engine's light: a radius in blocks and each channel from 0 to 15.
     */
    @Nonnull
    static ColorLight light(@Nonnull DieShape.Rgb rgb, double brightness) {
        double b = Math.max(0.0, Math.min(1.0, brightness));
        int radius = (int) Math.round(DieGlow.PEAK_RADIUS * Math.min(1.5, brightness));
        return new ColorLight((byte) Math.max(1, radius), channel(rgb.red(), b), channel(rgb.green(), b),
                channel(rgb.blue(), b));
    }

    private static byte channel(int value, double brightness) {
        return (byte) Math.round(value / 255.0 * 15.0 * brightness);
    }
}
