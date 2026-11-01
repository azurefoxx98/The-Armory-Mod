package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Remembers a stat's change currently written into some fields of a player's live movement
 * settings. Each subclass names the fields its stat scales.
 *
 * <p>Other code can replace the live settings while the change is on, a game mode applying its
 * own movement config for example. Keeping the values the change started from, and the values
 * it wrote, lets it recognise its own values, scale whatever replaced them, and undo exactly
 * its own change when the stat goes back to normal. Restoring the remembered values instead of
 * dividing by the multiplier keeps a multiplier of zero reversible.</p>
 */
public abstract class ScaledMovementState implements Component<EntityStore> {

    private boolean active;
    private float[] baseValues = new float[0];
    private float[] writtenValues = new float[0];

    public boolean isActive() {
        return active;
    }

    /**
     * Brings the live settings in line with the given multiplier.
     *
     * @param live the player's live movement settings, changed in place
     * @param multiplier the stat's multiplier, 1.0 for none
     * @return true when the live settings were changed and must be sent to the client
     */
    public boolean reconcile(@Nonnull MovementSettings live, float multiplier) {
        float[] liveValues = read(live);
        boolean ours = active && same(liveValues, writtenValues);

        if (Math.abs(multiplier - 1.0F) <= StatMultiplier.EPSILON) {
            active = false;
            if (!ours) {
                // Nothing of ours is in place, or someone else replaced it. Either way, leave it.
                return false;
            }
            write(live, baseValues);
            return true;
        }

        // While our change is in place the values it started from are still the base. Anything
        // else in the live settings is a new base, and the change is applied on top of it.
        float[] base = ours ? baseValues : liveValues;
        float[] target = scaled(base, multiplier);
        if (ours && same(writtenValues, target)) {
            return false;
        }

        write(live, target);
        baseValues = base;
        writtenValues = target;
        active = true;
        return true;
    }

    /** The values of the fields this stat scales, in a fixed order. */
    @Nonnull
    protected abstract float[] read(@Nonnull MovementSettings settings);

    /** Writes values in the order {@link #read} gives them. */
    protected abstract void write(@Nonnull MovementSettings settings, @Nonnull float[] values);

    /** A new, empty state of the same kind, for {@link #clone()}. */
    @Nonnull
    protected abstract ScaledMovementState newEmpty();

    @Nonnull
    @Override
    public final Component<EntityStore> clone() {
        ScaledMovementState copy = newEmpty();
        copy.active = active;
        copy.baseValues = baseValues.clone();
        copy.writtenValues = writtenValues.clone();
        return copy;
    }

    @Nonnull
    private static float[] scaled(@Nonnull float[] values, float multiplier) {
        float[] result = Arrays.copyOf(values, values.length);
        for (int i = 0; i < result.length; i++) {
            result[i] *= multiplier;
        }
        return result;
    }

    private static boolean same(@Nonnull float[] a, @Nonnull float[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > StatMultiplier.EPSILON) {
                return false;
            }
        }
        return true;
    }
}
