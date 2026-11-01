package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.protocol.BlockPosition;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which die a press aimed into grass takes. The ground is solid below y = 81, everything above it is grass the line
 * passes through, and the player's eye stands at y = 82.6 looking along +z and down.
 */
class PickUpThroughPlantsTest {

    private static final PickUpThroughPlants.Cells GROUND = (x, y, z) -> y < 81;

    private static Vector3d die(double x, double z) {
        return new Vector3d(x, 81.0 + PickUpThroughPlants.MIDDLE, z);
    }

    @Test
    void aDieInTheStruckPlantIsTaken() {
        Vector3d eye = new Vector3d(0.5, 82.6, 0.5);
        Vector3d hit = new Vector3d(0.5, 81.5, 3.2);
        assertEquals(0, PickUpThroughPlants.aimedAt(eye, hit, new BlockPosition(0, 81, 3), List.of(die(0.6, 3.5)), GROUND));
    }

    @Test
    void aDieOnePlantBehindTheStruckOneIsTaken() {
        // The crosshair meets the plant in front, cell z 3; the die lies in the next cell, on the same line.
        Vector3d eye = new Vector3d(0.5, 82.6, 0.5);
        Vector3d hit = new Vector3d(0.5, 81.6, 3.0);
        Vector3d dir = new Vector3d(hit).sub(eye).normalize();
        Vector3d onLine = new Vector3d(dir).mul(eye.distance(hit) + 1.0).add(eye);
        assertEquals(0, PickUpThroughPlants.aimedAt(eye, hit, new BlockPosition(0, 81, 3),
                List.of(die(onLine.x, onLine.z)), (x, y, z) -> y < 81));
    }

    @Test
    void aDieJustUnderTheLineBeforeTheStruckPlantIsTaken() {
        // Aimed a little high: the line passes 0.3 over the die's middle in cell z 2 and meets the plant at z 3.
        Vector3d eye = new Vector3d(0.5, 82.6, 0.5);
        Vector3d hit = new Vector3d(0.5, 81.6, 3.5);
        Vector3d dir = new Vector3d(hit).sub(eye).normalize();
        Vector3d over = new Vector3d(dir).mul(eye.distance(hit) - 1.0).add(eye);
        Vector3d middle = new Vector3d(over.x, over.y - 0.3, over.z);
        assertEquals(0, PickUpThroughPlants.aimedAt(eye, hit, new BlockPosition(0, 81, 3), List.of(middle), GROUND));
    }

    @Test
    void theNearestAlongTheLineIsTheOneTaken() {
        Vector3d eye = new Vector3d(0.5, 82.6, 0.5);
        Vector3d hit = new Vector3d(0.5, 81.4, 3.0);
        Vector3d dir = new Vector3d(hit).sub(eye).normalize();
        Vector3d near = new Vector3d(dir).mul(eye.distance(hit) + 0.6).add(eye);
        Vector3d far = new Vector3d(dir).mul(eye.distance(hit) + 1.8).add(eye);
        assertEquals(1, PickUpThroughPlants.aimedAt(eye, hit, new BlockPosition(0, 81, 3),
                List.of(die(far.x, far.z), die(near.x, near.z)), (x, y, z) -> false));
    }

    @Test
    void aDieBehindAWallIsNeverTaken() {
        // A flat line, so the die lies right on it past the wall's cell z = 4.
        Vector3d eye = new Vector3d(0.5, 81.9, 0.5);
        Vector3d hit = new Vector3d(0.5, 81.5, 3.0);
        Vector3d dir = new Vector3d(hit).sub(eye).normalize();
        Vector3d onLine = new Vector3d(dir).mul(eye.distance(hit) + 2.3).add(eye);
        List<Vector3d> dice = List.of(die(onLine.x, onLine.z));
        assertEquals(0, PickUpThroughPlants.aimedAt(eye, hit, new BlockPosition(0, 81, 3), dice, GROUND),
                "without the wall the die is the one aimed at");
        PickUpThroughPlants.Cells wall = (x, y, z) -> y < 81 || z == 4;
        assertEquals(-1, PickUpThroughPlants.aimedAt(eye, hit, new BlockPosition(0, 81, 3), dice, wall));
    }

    @Test
    void aDieOffToTheSideIsNotTheOneAimedAt() {
        Vector3d eye = new Vector3d(0.5, 82.6, 0.5);
        Vector3d hit = new Vector3d(0.5, 81.6, 3.0);
        assertEquals(-1, PickUpThroughPlants.aimedAt(eye, hit, new BlockPosition(0, 81, 3), List.of(die(2.8, 4.0)), GROUND));
    }

    @Test
    void aDieFarPastTheStruckPlantIsOutOfReach() {
        Vector3d eye = new Vector3d(0.5, 82.6, 0.5);
        Vector3d hit = new Vector3d(0.5, 81.6, 3.0);
        Vector3d dir = new Vector3d(hit).sub(eye).normalize();
        Vector3d far = new Vector3d(dir).mul(eye.distance(hit) + PickUpThroughPlants.BEYOND + 0.5).add(eye);
        assertEquals(-1, PickUpThroughPlants.aimedAt(eye, hit, new BlockPosition(0, 81, 3),
                List.of(die(far.x, far.z)), (x, y, z) -> false));
    }

    @Test
    void aSolidCellOnTheWayIsSeen() {
        PickUpThroughPlants.Cells block = (x, y, z) -> x == 2 && y == 81 && z == 2;
        assertEquals(false, PickUpThroughPlants.clear(new Vector3d(0.5, 81.5, 0.5), new Vector3d(4.5, 81.5, 4.5), block));
        assertEquals(true, PickUpThroughPlants.clear(new Vector3d(0.5, 81.5, 0.5), new Vector3d(0.5, 81.5, 4.5), block));
    }
}
