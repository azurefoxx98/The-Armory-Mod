package me.ladypaladra.thearmorymod.armorstand;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Cancels every damage event aimed at an armour stand's figure.
 *
 * <p>A figure is scenery rather than a creature, so nothing that happens to it should produce
 * drops or take it off display.</p>
 *
 * <p>This runs in the damage module's filter group, which is where the engine decides whether a
 * hit applies at all. The inspection group would be too late, because by then the only thing
 * left to do is react to damage that has already landed.</p>
 *
 * <p>Read the note on {@link MannequinShield} before treating this as the fix for the reported
 * duplication. This system cancels damage events, and the kill described in that report never
 * raised one, so it is not what stops it.</p>
 *
 * <p>Operators can switch it off with {@code -Darmorstand.mannequinInvulnerable=false}. Which
 * way it is set gets written to the log when the system registers.</p>
 */
public final class MannequinShieldSystem extends DamageEventSystem {

    public static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("armorstand.mannequinInvulnerable", "true"));

    private final Query<EntityStore> query;

    public MannequinShieldSystem() {
        this.query = Query.and(MannequinShield.getComponentType());
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> buffer, @Nonnull Damage damage) {
        if (ENABLED) {
            damage.setCancelled(true);
        }
    }
}
