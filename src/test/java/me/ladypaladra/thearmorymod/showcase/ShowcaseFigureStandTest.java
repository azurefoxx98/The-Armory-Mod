package me.ladypaladra.thearmorymod.showcase;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShowcaseFigureStandTest {

    /**
     * The fire guardians at the showcase's landing stood bare: their chunk is loaded before the
     * visitor arrives, it stops ticking after a few seconds with no player near it, and the engine
     * then holds the stand aside and puts the same component back when the chunk ticks again. The
     * stand has to build its figure again after that.
     */
    @Test
    void standBuildsItsFigureAgainAfterItsChunkWasParked() throws Exception {
        ShowcaseFigureStand stand = dressedStand();
        stand.attach();
        assertTrue(stand.wantsFigure());
        assertTrue(stand.finishSpawn(stand.beginSpawn()));

        // The chunk stops ticking: the stand leaves the world, and its figure, never saved, is gone.
        stand.detach();
        // The chunk ticks again: the same component returns.
        stand.attach();

        assertTrue(stand.wantsFigure(), "a stand back from a parked chunk must build its figure again");
        assertTrue(stand.finishSpawn(stand.beginSpawn()));
    }

    @Test
    void buildQueuedBeforeTheStandLeftIsDropped() throws Exception {
        ShowcaseFigureStand stand = dressedStand();
        stand.attach();
        int queued = stand.beginSpawn();

        stand.detach();
        stand.attach();

        assertFalse(stand.finishSpawn(queued), "a build queued before the stand left must not run");
        assertTrue(stand.wantsFigure());
    }

    @Test
    void standThatLeftBuildsNothing() throws Exception {
        ShowcaseFigureStand stand = dressedStand();
        stand.attach();
        int queued = stand.beginSpawn();

        stand.detach();

        assertFalse(stand.finishSpawn(queued));
        assertFalse(stand.wantsFigure());
    }

    @Test
    void standWaitsForItsQueuedBuild() throws Exception {
        ShowcaseFigureStand stand = dressedStand();
        stand.attach();
        stand.beginSpawn();

        assertFalse(stand.wantsFigure(), "one build at a time");
    }

    @Test
    void standNamingNothingBuildsNothing() {
        ShowcaseFigureStand stand = new ShowcaseFigureStand();
        stand.attach();

        assertFalse(stand.wantsFigure());
    }

    private static ShowcaseFigureStand dressedStand() throws Exception {
        ShowcaseFigureStand stand = new ShowcaseFigureStand();
        Field head = ShowcaseFigureStand.class.getDeclaredField("head");
        head.setAccessible(true);
        head.set(stand, "WardenHelmFire");
        return stand;
    }
}
