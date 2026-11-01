package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.protocol.ColorLight;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One roll's big number while it shows: what it reads, where it stands, how long it lives, and who has been given
 * their own tiles of it. A player who comes within reach while it still shows is given theirs, in step with everyone
 * else's.
 */
final class NumberShow {

    final Vector3d anchor;
    final String digits;
    final String texture;
    final RollBand band;
    final int life;
    final Set<UUID> viewers = new HashSet<>();

    /**
     * The light each tile casts, so its carved digit burns: the colour, how bright at full size from 0 to 1, and how
     * far it reaches in blocks. A model's colours are lit by the world like any other, so without a light of its own a
     * tile in shade reads as dull stone. The game's glowing creatures carry a light the same way.
     */
    final DieShape.Rgb glow;
    final double glowStrength;
    final int glowRadius;

    /**
     * Ticks since the number rose, kept by its tiles as they move.
     */
    int age;

    /**
     * The age at which latecomers were last looked for, so a roll with many viewers looks once per check.
     */
    int checkedAt = -1;

    NumberShow(@Nonnull Vector3d anchor, @Nonnull String digits, @Nonnull String texture, @Nonnull RollBand band,
               int life, @Nonnull DieShape.Rgb glow, double glowStrength, int glowRadius) {
        this.anchor = new Vector3d(anchor);
        this.digits = digits;
        this.texture = texture;
        this.band = band;
        this.life = life;
        this.glow = glow;
        this.glowStrength = glowStrength;
        this.glowRadius = glowRadius;
    }

    /**
     * The tile's light at a size, as a share of its full size: it swells as the tile pops up and dies as it shrinks.
     */
    @Nonnull
    ColorLight light(double sizeShare) {
        double share = Math.max(0.0, Math.min(1.0, sizeShare));
        return LandedDieSystems.light(glow, glowStrength * share, (int) Math.round(glowRadius * Math.max(0.5, share)));
    }
}
