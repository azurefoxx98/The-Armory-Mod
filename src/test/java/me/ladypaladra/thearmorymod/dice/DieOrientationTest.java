package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DieOrientationTest {

    @Test
    void aTurnSentAsAnglesComesBackTheSameThroughTheEngine() {
        // The plugin writes a die's turn as the engine's yaw, pitch and roll. Read back by the engine's own
        // Rotation3f, it must be the same turn, or every die would be drawn turned some other way.
        SplittableRandom rng = new SplittableRandom(3);
        for (int i = 0; i < 2000; i++) {
            Quaterniond q = DieOrientation.uniform(rng, new Quaterniond());
            Vector3d e = q.getEulerAnglesYXZ(new Vector3d());
            Rotation3f rotation = new Rotation3f((float) e.x, (float) e.y, (float) e.z);
            Quaterniond back = rotation.getQuaternion(new Quaterniond());
            assertTrue(Math.abs(back.dot(q)) > 1.0 - 1.0E-6, "angles " + e + " are not " + q);
        }
    }

    @Test
    void theRestingYawKeepsAFaceUpDieWhereItIs() throws IOException {
        DieShape d20 = DiceCatalog.load().shape("D20");
        SplittableRandom rng = new SplittableRandom(4);
        for (DieShape.Face face : d20.faces()) {
            double yaw = DieOrientation.wrap(rng.nextDouble() * 7.0);
            Quaterniond lying = DieOrientation.resting(yaw, face, new Quaterniond());
            assertSame(face, DieOrientation.topFace(lying, d20));
            assertEquals(0.0, DieOrientation.wrap(DieOrientation.restYaw(lying, face) - yaw), 1.0E-9);
        }
    }

    @Test
    void theRestingYawIsTheClosestOfAllYaws() throws IOException {
        DieShape d8 = DiceCatalog.load().shape("D8");
        SplittableRandom rng = new SplittableRandom(5);
        for (int i = 0; i < 300; i++) {
            Quaterniond q = DieOrientation.uniform(rng, new Quaterniond());
            DieShape.Face top = DieOrientation.topFace(q, d8);
            double chosen = Math.abs(DieOrientation.resting(DieOrientation.restYaw(q, top), top, new Quaterniond()).dot(q));
            for (int k = 0; k < 72; k++) {
                double other = Math.abs(DieOrientation.resting(k * Math.PI / 36.0, top, new Quaterniond()).dot(q));
                assertTrue(chosen >= other - 1.0E-12, "yaw " + k * 5 + " degrees is closer");
            }
        }
    }

    @Test
    void theTopFaceIsTheOnePointingUp() throws IOException {
        DieShape d6 = DiceCatalog.load().shape("D6");
        for (DieShape.Face face : d6.faces()) {
            assertSame(face, DieOrientation.topFace(new Quaterniond(face.restTurn()), d6));
        }
    }
}
