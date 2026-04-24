package me.ladypaladra.thearmorymod.stats.systems;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.stats.ArmoryStatService;
import me.ladypaladra.thearmorymod.stats.util.ProjectileMatchUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class ArrowDamageBonusSystem extends DamageEventSystem {

    private static final List<String> ARROW_PROJECTILE_PATTERNS = List.of(
            "Arrow*",
            "*Arrow",
            "*arrow*"
    );

    private static final List<String> BOW_ITEM_PATTERNS = List.of(
            "*Bow*",
            "*bow*",
            "*Shortbow*",
            "*shortbow*",
            "*Longbow*",
            "*longbow*",
            "*Crossbow*",
            "*crossbow*"
    );

    private final ArmoryStatService statService;

    public ArrowDamageBonusSystem(ArmoryStatService statService) {
        this.statService = statService;
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> chunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull Damage damage) {

        if (damage.getAmount() <= 0.0f) return;

        DamageCause cause = damage.getCause();
        String causeId = cause != null ? cause.getId() : "null";

        Damage.Source source = damage.getSource();
        Ref<EntityStore> attackerRef;
        Ref<EntityStore> projectileRef;

        boolean matchedArrow = false;

        if (source instanceof Damage.ProjectileSource projectileSource) {
            attackerRef = projectileSource.getRef();
            projectileRef = projectileSource.getProjectile();

            if (attackerRef.isValid() && projectileRef.isValid()) {
                matchedArrow = ProjectileMatchUtil.matchesProjectileAsset(
                        store,
                        commandBuffer,
                        projectileRef,
                        ARROW_PROJECTILE_PATTERNS
                );
            }
        } else if (source instanceof Damage.EntitySource entitySource) {
            attackerRef = entitySource.getRef();

            String heldItemId = getHeldItemId(store, commandBuffer, attackerRef);
            boolean projectileCause = "Projectile".equalsIgnoreCase(causeId);
            boolean bowMatched = matchesAnyPattern(heldItemId);

            if (attackerRef.isValid() && projectileCause && bowMatched) {
                matchedArrow = true;
            }
        } else {
            return;
        }

        if (!matchedArrow) return;

        if (!attackerRef.isValid()) return;

        float bonusMultiplier = statService.getArrowDamageBonus(store, commandBuffer, attackerRef);

        if (bonusMultiplier <= 0.0f) return;

        float before = damage.getAmount();
        float after = before * bonusMultiplier;

        damage.setAmount(after);
    }

    private static String getHeldItemId(@Nonnull Store<EntityStore> store,
                                        @Nonnull CommandBuffer<EntityStore> commandBuffer,
                                        @Nullable Ref<EntityStore> attackerRef) {
        if (attackerRef == null || !attackerRef.isValid()) {
            return "null";
        }

        ItemStack heldItem = InventoryComponent.getItemInHand(commandBuffer, attackerRef);
        if (heldItem == null) {
            heldItem = InventoryComponent.getItemInHand(store, attackerRef);
        }

        return heldItem != null ? heldItem.getItemId() : "null";
    }

    private static boolean matchesAnyPattern(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        for (String pattern : ArrowDamageBonusSystem.BOW_ITEM_PATTERNS) {
            if (wildcardMatch(value, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean wildcardMatch(@Nonnull String value, @Nonnull String pattern) {
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*");
        return value.matches(regex);
    }
}