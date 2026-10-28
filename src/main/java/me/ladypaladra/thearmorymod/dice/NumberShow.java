package me.ladypaladra.thearmorymod.dice;

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
     * Ticks since the number rose, kept by its tiles as they move.
     */
    int age;

    /**
     * The age at which latecomers were last looked for, so a roll with many viewers looks once per check.
     */
    int checkedAt = -1;

    NumberShow(@Nonnull Vector3d anchor, @Nonnull String digits, @Nonnull String texture, @Nonnull RollBand band,
               int life) {
        this.anchor = new Vector3d(anchor);
        this.digits = digits;
        this.texture = texture;
        this.band = band;
        this.life = life;
    }
}
