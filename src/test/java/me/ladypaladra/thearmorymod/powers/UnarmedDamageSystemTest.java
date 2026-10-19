package me.ladypaladra.thearmorymod.powers;

import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnarmedDamageSystemTest {

    // Source classification does not read entity references or require a running world.
    private static Damage hit(Damage.Source source, InteractionType type, float amount) {
        Damage damage = new Damage(source, 0, amount);
        if (type != null) {
            damage.putMetaObject(Damage.INTERACTION_TYPE, type);
        }
        return damage;
    }

    @Test
    void aPrimaryAttackFromThePlayerCounts() {
        assertTrue(UnarmedDamageSystem.isPrimaryDirectHit(hit(new Damage.EntitySource(null), InteractionType.Primary, 1.0F)));
    }

    @Test
    void aReflectionCarriesNoAttackClickAndDoesNotCount() {
        // A Mirror Shield reflection is dealt straight through the damage pipeline, untagged.
        assertFalse(UnarmedDamageSystem.isPrimaryDirectHit(hit(new Damage.EntitySource(null), null, 5.0F)));
    }

    @Test
    void secondaryAttacksAndOtherClicksDoNotCount() {
        assertFalse(UnarmedDamageSystem.isPrimaryDirectHit(hit(new Damage.EntitySource(null), InteractionType.Secondary, 1.0F)));
        assertFalse(UnarmedDamageSystem.isPrimaryDirectHit(hit(new Damage.EntitySource(null), InteractionType.Ability1, 1.0F)));
    }

    @Test
    void projectilesAndEffectsDoNotCount() {
        assertFalse(UnarmedDamageSystem.isPrimaryDirectHit(hit(new Damage.ProjectileSource(null, null), InteractionType.Primary, 1.0F)));
        assertFalse(UnarmedDamageSystem.isPrimaryDirectHit(hit(new Damage.EntityEffectSource(null, null), InteractionType.Primary, 1.0F)));
        assertFalse(UnarmedDamageSystem.isPrimaryDirectHit(hit(Damage.NULL_SOURCE, InteractionType.Primary, 1.0F)));
    }

    @Test
    void cancelledAndEmptyHitsDoNotCount() {
        Damage cancelled = hit(new Damage.EntitySource(null), InteractionType.Primary, 1.0F);
        cancelled.setCancelled(true);
        assertFalse(UnarmedDamageSystem.isPrimaryDirectHit(cancelled));
        assertFalse(UnarmedDamageSystem.isPrimaryDirectHit(hit(new Damage.EntitySource(null), InteractionType.Primary, 0.0F)));
    }
}
