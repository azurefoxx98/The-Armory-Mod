package me.ladypaladra.thearmorymod.dice;

import org.joml.Quaterniondc;
import org.joml.Vector3dc;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * One physical die as the art built it: its colour, its flying model, its size, and per face what it reads and how it
 * lies when that face is up.
 *
 * <p>Every direction is in the flying model's own frame, the frame the die tumbles in. A face's {@code restTurn} is
 * the turn baked into that face's resting model: it carries the face's {@code normal} straight up, so the face the
 * motion finds on top and the model that shows it can never disagree.</p>
 *
 * @param key         the shape's key in the data, such as {@code D20} or {@code D100_Tens}
 * @param colour      the glow colour every model and effect of this die is drawn in
 * @param glow        that colour's edge as red, green and blue from 0 to 255, the colour the die's light is cast in
 * @param flyingModel the model asset of the die in flight
 * @param radius      blocks from the die's middle to its farthest corner
 * @param faces       one entry per result the die can show
 */
public record DieShape(@Nonnull String key, @Nonnull String colour, @Nonnull Rgb glow, @Nonnull String flyingModel,
                       double radius, @Nonnull List<Face> faces) {

    /**
     * A colour, each channel from 0 to 255.
     */
    public record Rgb(int red, int green, int blue) {
    }

    /**
     * One result of a die.
     *
     * @param index      the face's place in the die's list
     * @param label      what the face reads, as carved
     * @param normal     the face's outward direction, a unit vector (for the D4, the corner that is read)
     * @param restTurn   the turn that brings {@code normal} straight up
     * @param restHeight blocks from the ground to the top of the resting model
     * @param restMiddle blocks from the ground to the die's middle when it rests on this face
     * @param restModel  the model asset of the die lying with this face up
     */
    public record Face(int index, @Nonnull String label, @Nonnull Vector3dc normal, @Nonnull Quaterniondc restTurn,
                       double restHeight, double restMiddle, @Nonnull String restModel) {
    }
}
