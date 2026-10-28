package me.ladypaladra.thearmorymod.dice;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The face a player sees on top is the face the server reads. The game composes the angles it is sent as yaw, pitch
 * and roll (Rotation3f#getQuaternion, rotationYXZ) and draws the model half a turn about its own upright axis from
 * that, so what is drawn is the sent turn followed by that half turn.
 */
class DrawnTurnTest {

    private static final Vector3d UP = new Vector3d(0, 1, 0);

    @Test
    void theFaceDrawnOnTopIsTheFaceReadInFlight() throws IOException {
        DiceCatalog catalog = DiceCatalog.load();
        SplittableRandom rng = new SplittableRandom(28);
        for (DieShape shape : catalog.shapes().values()) {
            for (int i = 0; i < 2000; i++) {
                Quaterniond held = DieOrientation.uniform(rng, new Quaterniond());
                Quaterniond drawn = drawn(DieFlightSystems.sentAngles(held));
                assertTrue(sameTurn(held, drawn), shape.key() + ": drawn " + drawn + ", held " + held);
                assertEquals(top(shape, held), top(shape, drawn), shape.key());
            }
        }
    }

    @Test
    void sendingTheTurnUnchangedWouldShowAnotherFace() throws IOException {
        // The control: without the half turn, the D20 shows a different face on top most of the time, as it did in
        // game, while the D3, whose opposite faces carry one number, never does.
        DiceCatalog catalog = DiceCatalog.load();
        SplittableRandom rng = new SplittableRandom(29);
        int d20Wrong = 0;
        int d3Wrong = 0;
        for (int i = 0; i < 2000; i++) {
            Quaterniond held = DieOrientation.uniform(rng, new Quaterniond());
            Quaterniond drawnUnchanged = drawn(held.getEulerAnglesYXZ(new Vector3d()));
            if (!top(catalog.shape("D20"), held).equals(top(catalog.shape("D20"), drawnUnchanged))) {
                d20Wrong++;
            }
            if (!top(catalog.shape("D3"), held).equals(top(catalog.shape("D3"), drawnUnchanged))) {
                d3Wrong++;
            }
        }
        assertTrue(d20Wrong > 1500, d20Wrong + " of 2000 D20 throws");
        assertEquals(0, d3Wrong);
    }

    @Test
    void aLandedDieIsDrawnTurnedAsItRests() {
        for (double rest = -3.1; rest < 3.2; rest += 0.37) {
            Quaterniond drawn = new Quaterniond().rotationY(DieFlightSystems.sentRestYaw(rest))
                    .mul(DieFlightSystems.DRAWN_HALF_TURN);
            assertTrue(sameTurn(new Quaterniond().rotationY(rest), drawn), "rest yaw " + rest);
        }
    }

    /**
     * What the game draws for the angles it is sent.
     */
    private static Quaterniond drawn(Vector3d angles) {
        return new Quaterniond().rotationYXZ(angles.y, angles.x, angles.z).mul(DieFlightSystems.DRAWN_HALF_TURN);
    }

    private static String top(DieShape shape, Quaterniond turn) {
        DieShape.Face best = null;
        double most = -2;
        for (DieShape.Face face : shape.faces()) {
            double up = turn.transform(new Vector3d(face.normal())).dot(UP);
            if (up > most) {
                most = up;
                best = face;
            }
        }
        return best.label();
    }

    private static boolean sameTurn(Quaterniond a, Quaterniond b) {
        return Math.abs(Math.abs(a.dot(b)) - 1.0) < 1.0E-6;
    }
}
