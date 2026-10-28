package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * A landed die casting its colour as light while its mark awakens: a flare that fades over a second and a half.
 * Never saved: a die reloaded from disk lies quietly, its top mark still lit in its model.
 */
public final class DieGlow implements Component<EntityStore> {

    /**
     * Ticks the glow lasts, and the light's radius at its brightest, in blocks.
     */
    static final int LIFE_TICKS = 45;
    static final int PEAK_RADIUS = 5;

    final DieShape.Rgb colour;
    int age;
    /**
     * The extremes hold their light longer and brighter.
     */
    double strength = 1.0;

    DieGlow(@Nonnull DieShape.Rgb colour) {
        this.colour = colour;
    }

    /**
     * How bright the glow is at its current age, from 0 to 1: up in a few ticks, then a slow fade.
     */
    double brightness() {
        int life = (int) (LIFE_TICKS * strength);
        if (age >= life) {
            return 0.0;
        }
        if (age < 4) {
            return (age + 1) / 4.0;
        }
        double t = (double) (age - 4) / Math.max(1, life - 4);
        return (1.0 - t) * (1.0 - t);
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        DieGlow copy = new DieGlow(colour);
        copy.age = age;
        copy.strength = strength;
        return copy;
    }
}
