package me.ladypaladra.thearmorymod.dice;

import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * A flying die's place and motion. {@code position} is its centre of weight in the world, and its turn and spin are the
 * flight's own, so the turn the physics computes is the turn the client is sent.
 */
final class DieBody {

    final Vector3d position = new Vector3d();
    final Vector3d velocity = new Vector3d();
    final DieMotion.Spin spin;

    DieBody(@Nonnull DieMotion.Spin spin) {
        this.spin = spin;
    }
}
