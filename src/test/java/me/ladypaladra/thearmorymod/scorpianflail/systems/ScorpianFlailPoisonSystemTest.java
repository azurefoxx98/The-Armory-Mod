package me.ladypaladra.thearmorymod.scorpianflail.systems;

import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScorpianFlailPoisonSystemTest {

    // Source classification does not read entity references or require a running world.
    private static Damage melee(float amount) {
        return new Damage(new Damage.EntitySource(null), 0, amount);
    }

    @Test
    void anAcceptedMeleeHitCanProc() {
        assertTrue(ScorpianFlailPoisonSystem.canApplyPoison(melee(1.0F)));
        assertTrue(ScorpianFlailPoisonSystem.canApplyPoison(melee(100.0F)));
    }

    @Test
    void cancelledAndFullyMitigatedHitsCannotProc() {
        Damage damage = melee(10.0F);
        damage.setCancelled(true);
        assertFalse(ScorpianFlailPoisonSystem.canApplyPoison(damage));
        assertFalse(ScorpianFlailPoisonSystem.canApplyPoison(melee(0.0F)));
        assertFalse(ScorpianFlailPoisonSystem.canApplyPoison(melee(-1.0F)));
    }

    @Test
    void switchingToTheFlailCannotPoisonAnInFlightArrow() {
        Damage damage = new Damage(new Damage.ProjectileSource(null, null), 0, 10.0F);
        assertFalse(ScorpianFlailPoisonSystem.canApplyPoison(damage));
    }

    @Test
    void attributedPoisonCannotProcAnotherPoison() {
        Damage damage = new Damage(new Damage.EntityEffectSource(null, null), 0, 10.0F);
        assertFalse(ScorpianFlailPoisonSystem.canApplyPoison(damage));
    }

    @Test
    void environmentalDamageCannotProc() {
        Damage damage = new Damage(Damage.NULL_SOURCE, 0, 10.0F);
        assertFalse(ScorpianFlailPoisonSystem.canApplyPoison(damage));
    }
}
