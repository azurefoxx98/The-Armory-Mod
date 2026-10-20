package me.ladypaladra.thearmorymod.powers;

import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReflectDamageInteractionTest {

    private static final float DELTA = 0.00001F;
    private static final int PHYSICAL = 0;
    private static final int REFLECTED = 7;

    // The decision reads only the hit itself, so no world or entity is needed.
    private static Damage hit(int cause, float amount) {
        return new Damage(new Damage.EntitySource(null), cause, amount);
    }

    @Test
    void halfOfAHitThatLandedIsSentBack() {
        assertEquals(5.0F, ReflectDamageInteraction.reflectedAmount(hit(PHYSICAL, 10.0F), REFLECTED, 0.5F), DELTA);
    }

    @Test
    void theShareIsTakenFromTheHitAsFirstDealtNotAfterTheGuard() {
        Damage blocked = hit(PHYSICAL, 10.0F);
        blocked.setAmount(0.0F);
        assertEquals(5.0F, ReflectDamageInteraction.reflectedAmount(blocked, REFLECTED, 0.5F), DELTA);
    }

    @Test
    void aHitTheGameCancelledIsNotSentBack() {
        // A filter that runs after the guard, such as another mod's ward, cancels the hit.
        Damage cancelled = hit(PHYSICAL, 10.0F);
        cancelled.setCancelled(true);
        assertEquals(0.0F, ReflectDamageInteraction.reflectedAmount(cancelled, REFLECTED, 0.5F), DELTA);
    }

    @Test
    void aZeroDamageProbeIsNotSentBack() {
        assertEquals(0.0F, ReflectDamageInteraction.reflectedAmount(hit(PHYSICAL, 0.0F), REFLECTED, 0.5F), DELTA);
    }

    @Test
    void aReflectionIsNeverReflectedAgain() {
        assertEquals(0.0F, ReflectDamageInteraction.reflectedAmount(hit(REFLECTED, 10.0F), REFLECTED, 0.5F), DELTA);
    }

    @Test
    void aMissingReflectionCauseSendsNothing() {
        assertEquals(0.0F, ReflectDamageInteraction.reflectedAmount(hit(PHYSICAL, 10.0F), -1, 0.5F), DELTA);
    }
}
