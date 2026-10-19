package me.ladypaladra.thearmorymod.powers;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrePackMiningSpeedTest {

    private static final float MULTIPLIER = 1.3F;

    // Mines one block of health 1 with the given per-hit damage and returns the hits it took.
    private static int hitsToBreak(float damage, Random random) {
        float health = 1.0F;
        int hits = 0;
        while (health > 1.0E-5F) {
            health -= OrePackSystems.MiningSpeed.boostedDamage(health, damage, MULTIPLIER, random.nextFloat());
            hits++;
        }
        return hits;
    }

    private static double averageHits(float damage) {
        Random random = new Random(20261019L);
        long total = 0;
        int blocks = 100_000;
        for (int i = 0; i < blocks; i++) {
            total += hitsToBreak(damage, random);
        }
        return (double) total / blocks;
    }

    @Test
    void ironOreWithAnIronPickaxeIsThirtyPercentFasterOnAverage() {
        // Four hits of 0.25 without the pack; 1 / (0.25 * 1.3) = 3.077 with it.
        assertEquals(1.0 / (0.25 * MULTIPLIER), averageHits(0.25F), 0.02);
    }

    @Test
    void stoneIsThirtyPercentFasterOnAverage() {
        // Two hits of 0.5 without the pack, which a plain multiplier never changes.
        assertEquals(1.0 / (0.5 * MULTIPLIER), averageHits(0.5F), 0.02);
    }

    @Test
    void thoriumIsThirtyPercentFasterOnAverage() {
        assertEquals(1.0 / (0.125 * MULTIPLIER), averageHits(0.125F), 0.02);
    }

    @Test
    void aHitThatAlreadyBreaksTheBlockIsOnlyMultiplied() {
        assertEquals(0.5F * MULTIPLIER, OrePackSystems.MiningSpeed.boostedDamage(0.4F, 0.5F, MULTIPLIER, 0.99F), 1.0E-6F);
    }

    @Test
    void theEarlyBreakNeverDealsMoreThanTheHealthLeft() {
        // 0.025 would be left after this hit, so a high roll breaks it now by dealing exactly 0.35.
        assertEquals(0.35F, OrePackSystems.MiningSpeed.boostedDamage(0.35F, 0.25F, MULTIPLIER, 0.99F), 1.0E-6F);
        // A low roll keeps the plain multiplied hit.
        assertEquals(0.325F, OrePackSystems.MiningSpeed.boostedDamage(0.35F, 0.25F, MULTIPLIER, 0.0F), 1.0E-6F);
    }
}
