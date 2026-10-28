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
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.modules.entity.component.DynamicLight;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
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
    static Ref<EntityStore> partner(@Nonnull UUID pair, @Nullable Ref<EntityStore> self) {
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
            if (die != null && reason == AddReason.LOAD) {
                settle(die, commandBuffer.getComponent(ref, TransformComponent.getComponentType()));
            }
            UUID pair = die != null ? die.pair() : null;
            if (pair == null) {
                return;
            }
            if (DiceModule.ledger().consumeCollected(pair)) {
                die.claim();
                commandBuffer.removeEntity(ref, RemoveReason.REMOVE);
                return;
            }
            PAIRS.computeIfAbsent(pair, k -> new ConcurrentHashMap<>()).put(ref, Boolean.TRUE);
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                                   @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            LandedDie die = store.getComponent(ref, DiceModule.landedType());
            if (die == null) {
                return;
            }
            UUID pair = die.pair();
            if (pair != null) {
                PAIRS.computeIfPresent(pair, (k, dice) -> {
                    dice.remove(ref);
                    return dice.isEmpty() ? null : dice;
                });
            }
            if (reason == RemoveReason.REMOVE && !die.claimed()) {
                returnToOwner(die, pair, commandBuffer);
            }
        }
    }

    /**
     * A landed die the world removed rather than a player: laid where no chunk can hold it, or cleared by anything but
     * a pickup. Its owner gets the item back, once, and a D100's other die leaves with it, since the item is the pair.
     * Unloading is not removal: an unloaded die is saved with its chunk.
     */
    private static void returnToOwner(@Nonnull LandedDie die, @Nullable UUID pair,
                                      @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        die.claim();
        if (pair != null) {
            Ref<EntityStore> partner = partner(pair, null);
            LandedDie other = partner != null ? commandBuffer.getComponent(partner, DiceModule.landedType()) : null;
            if (other != null && other.claim()) {
                commandBuffer.removeEntity(partner, RemoveReason.REMOVE);
            } else if (partner == null) {
                DiceModule.ledger().pairCollected(pair);
            } else {
                // The other die was already being taken: the pair is accounted for there.
                return;
            }
        }
        UUID owner = die.owner();
        if (owner != null && die.item() != null) {
            DiceModule.ledger().owe(owner, die.item());
        }
    }

    /**
     * A die saved in the middle of a lift or a shudder is put back where it came to rest.
     */
    static void settle(@Nonnull LandedDie die, @Nullable TransformComponent transform) {
        if (transform == null) {
            return;
        }
        if (!Double.isNaN(die.restY())) {
            transform.getPosition().y = die.restY();
        }
        if (!Double.isNaN(die.restYaw())) {
            transform.getRotation().set(0.0f, (float) die.restYaw(), 0.0f);
        }
    }

    /**
     * Plays each landed die's moment of show (DieGlow): its light, and for the extremes its lift or shudder and its
     * dark marks. When the show ends the die lies exactly where it came to rest, lit, and the show's state goes.
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
            DynamicLight light = chunk.getComponent(index, DynamicLight.getComponentType());
            TransformComponent transform = chunk.getComponent(index, TransformComponent.getComponentType());
            ModelComponent model = chunk.getComponent(index, ModelComponent.getComponentType());

            if (glow.over()) {
                if (glow.movesTheDie()) {
                    place(glow, transform, 0.0, 0.0);
                    marks(ref, glow, model, false, commandBuffer);
                }
                commandBuffer.removeComponent(ref, DiceModule.glowType());
                if (light != null) {
                    commandBuffer.removeComponent(ref, DynamicLight.getComponentType());
                }
                return;
            }

            double b = glow.brightness();
            if (b <= 0.0) {
                if (light != null) {
                    commandBuffer.removeComponent(ref, DynamicLight.getComponentType());
                }
            } else {
                ColorLight colour = light(glow.colour(), b, glow.radius());
                if (light == null) {
                    commandBuffer.addComponent(ref, DynamicLight.getComponentType(), new DynamicLight(colour));
                } else {
                    light.setColorLight(colour);
                }
            }
            if (glow.movesTheDie()) {
                place(glow, transform, glow.lift(), glow.turn());
                marks(ref, glow, model, glow.marksDark(), commandBuffer);
            }
            glow.age++;
        }
    }

    private static void place(@Nonnull DieGlow glow, @Nullable TransformComponent transform, double lift, double turn) {
        if (transform == null || glow.rest == null) {
            return;
        }
        transform.getPosition().set(glow.rest).add(0.0, lift, 0.0);
        transform.getRotation().set(0.0f, (float) wrap(glow.restYaw + turn), 0.0f);
    }

    /**
     * An angle brought into -pi to pi, the range every yaw the engine computes lies in, so the Favour's full turn
     * ends on the same value it started from instead of one turn away.
     */
    static double wrap(double angle) {
        double a = Math.IEEEremainder(angle, Math.PI * 2.0);
        return a <= -Math.PI ? a + Math.PI * 2.0 : a;
    }

    /**
     * Swaps the live model's texture when the marks go dark or light again. Only the live model changes: the saved one
     * always reloads lit.
     */
    private static void marks(@Nonnull Ref<EntityStore> ref, @Nonnull DieGlow glow, @Nullable ModelComponent model,
                              boolean dark, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (model == null || dark == glow.shownDark || glow.texture == null || glow.darkTexture == null) {
            return;
        }
        glow.shownDark = dark;
        Model now = DiceShow.retextured(model.getModel(), dark ? glow.darkTexture : glow.texture);
        commandBuffer.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(now));
    }

    /**
     * The engine's light: a radius in blocks and each channel from 0 to 15.
     */
    @Nonnull
    static ColorLight light(@Nonnull DieShape.Rgb rgb, double brightness, int radius) {
        double b = Math.max(0.0, Math.min(1.0, brightness));
        return new ColorLight((byte) Math.max(1, Math.min(15, radius)), channel(rgb.red(), b), channel(rgb.green(), b),
                channel(rgb.blue(), b));
    }

    private static byte channel(int value, double brightness) {
        return (byte) Math.round(value / 255.0 * 15.0 * brightness);
    }
}
