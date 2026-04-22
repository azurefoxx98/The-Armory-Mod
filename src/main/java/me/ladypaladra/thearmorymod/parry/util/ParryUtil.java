package me.ladypaladra.thearmorymod.parry.util;

import com.hypixel.hytale.server.core.modules.entity.damage.Damage;

public final class ParryUtil {

    private ParryUtil() {
    }

    public static void cancelParryDamage(Damage damage) {
        damage.setCancelled(true);
        damage.setAmount(0.0F);
        damage.putMetaObject(Damage.BLOCKED, true);
        damage.putMetaObject(Damage.STAMINA_DRAIN_MULTIPLIER, 0.0F);
    }
}