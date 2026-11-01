package me.ladypaladra.thearmorymod.dice;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DieGlowTest {

    private static final DieShape.Rgb PURPLE = new DieShape.Rgb(186, 78, 232);

    @Test
    void everyShowEndsWithTheDieLitAndStillWhereItLanded() {
        for (DieGlow.Style style : DieGlow.Style.values()) {
            for (RollBand.Tier tier : RollBand.Tier.values()) {
                DieGlow glow = show(style, tier);
                for (glow.age = 0; !glow.over(); glow.age++) {
                    assertTrue(glow.brightness() >= 0.0 && glow.brightness() <= 1.0, at(glow));
                    assertTrue(glow.radius() >= 1 && glow.radius() <= 15, at(glow));
                }
                // The last tick of the show already lies still: the system's final placement changes nothing.
                glow.age = glow.life() - 1;
                assertEquals(0.0, glow.lift(), 1.0E-9, at(glow));
                assertEquals(0.0, glow.turn(), 1.0E-9, at(glow));
                assertFalse(glow.marksDark(), at(glow));
                glow.age = glow.life();
                assertEquals(0.0, glow.brightness(), at(glow));
            }
        }
    }

    @Test
    void theFavourLiftsTheDieHalfABlockFromAD20Up() {
        assertEquals(0.0, peakLift(show(DieGlow.Style.FAVOUR, RollBand.Tier.MINOR)));
        assertEquals(0.5, peakLift(show(DieGlow.Style.FAVOUR, RollBand.Tier.MAJOR)), 0.05);
        assertEquals(0.75, peakLift(show(DieGlow.Style.FAVOUR, RollBand.Tier.GRAND)), 0.05);
        assertEquals(0.0, peakLift(show(DieGlow.Style.VEIL, RollBand.Tier.GRAND)));
    }

    @Test
    void theFavourTurnsTheDieOnceAndSmoothly() {
        DieGlow glow = show(DieGlow.Style.FAVOUR, RollBand.Tier.MAJOR);
        double last = 0.0;
        double most = 0.0;
        for (glow.age = 0; !glow.over(); glow.age++) {
            double turn = glow.turn();
            // Never a jump: at most a tenth of a turn a tick, measured the short way round as the die is drawn (the
            // end of the full turn and its start are the same direction), so it reads as a turn and not a snap.
            assertTrue(Math.abs(LandedDieSystems.wrap(turn - last)) < Math.PI / 5, at(glow));
            most = Math.max(most, turn);
            last = turn;
        }
        assertTrue(most > Math.PI * 1.9, "the die turned " + most + " radians");
    }

    @Test
    void theVeilPutsTheMarksOutUntilTheRiftClosesThenTheyComeBack() {
        assertFalse(darkAtAll(show(DieGlow.Style.VEIL, RollBand.Tier.MINOR)));
        for (RollBand.Tier tier : new RollBand.Tier[]{RollBand.Tier.MAJOR, RollBand.Tier.GRAND}) {
            DieGlow glow = show(DieGlow.Style.VEIL, tier);
            glow.age = DieGlow.VEIL_DARK - 1;
            assertFalse(glow.marksDark());
            glow.age = DieGlow.VEIL_DARK;
            assertTrue(glow.marksDark());
            glow.age = tier == RollBand.Tier.GRAND ? DieGlow.VEIL_RELIGHT_GRAND - 1 : DieGlow.VEIL_RELIGHT_MAJOR - 1;
            assertTrue(glow.marksDark());
            assertEquals(DieGlow.CRIMSON, glow.colour());
            glow.age++;
            assertFalse(glow.marksDark());
            // The marks come back in the die's own colour.
            assertEquals(PURPLE, glow.colour());
        }
    }

    @Test
    void theFavourShinesGoldAndFurtherThanAnAwakening() {
        DieGlow awaken = new DieGlow(PURPLE);
        DieGlow favour = show(DieGlow.Style.FAVOUR, RollBand.Tier.MAJOR);
        awaken.age = 10;
        favour.age = 30;
        assertEquals(DieGlow.GOLD, favour.colour());
        assertTrue(favour.radius() > awaken.radius());
        assertTrue(favour.life() > awaken.life());
    }

    private static DieGlow show(DieGlow.Style style, RollBand.Tier tier) {
        DieGlow glow = new DieGlow(PURPLE);
        if (style != DieGlow.Style.AWAKEN) {
            glow.begin(style, tier, new Vector3d(1, 64, 2), 0.5f, "lit.png", "dark.png");
        }
        return glow;
    }

    private static double peakLift(DieGlow glow) {
        double most = 0.0;
        for (glow.age = 0; !glow.over(); glow.age++) {
            most = Math.max(most, glow.lift());
        }
        return most;
    }

    private static boolean darkAtAll(DieGlow glow) {
        for (glow.age = 0; !glow.over(); glow.age++) {
            if (glow.marksDark()) {
                return true;
            }
        }
        return false;
    }

    private static String at(DieGlow glow) {
        return glow.style + " " + glow.tier + " at tick " + glow.age;
    }
}
