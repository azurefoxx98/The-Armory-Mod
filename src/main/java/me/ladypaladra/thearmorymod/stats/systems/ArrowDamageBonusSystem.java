package me.ladypaladra.thearmorymod.stats.systems;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
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

    private static final String BOW_FAMILY_TAG = "Family=Bow";
    private static final String CROSSBOW_FAMILY_TAG = "Family=Crossbow";

    // Tag indexes only exist after asset load. Resolve them on the first damage check and
    // keep them for later checks instead of reading an incomplete map during class loading.
    private static volatile int[] bowFamilyTagIndexes;

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

        // Resolve the cause index directly because Damage.getCause() is deprecated in Hytale 0.5.9.
        DamageCause cause = DamageCause.getAssetMap().getAsset(damage.getDamageCauseIndex());
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
            Item heldItem = Item.getAssetMap().getAsset(heldItemId);
            boolean projectileCause = "Projectile".equalsIgnoreCase(causeId);
            boolean bowMatched = matchesBowFamily(heldItem);

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

    static boolean matchesBowFamily(@Nullable Item item) {
        if (item == null || item.getData() == null) {
            return false;
        }

        for (int tagIndex : getBowFamilyTagIndexes()) {
            if (tagIndex != AssetRegistry.TAG_NOT_FOUND
                    && item.getData().getExpandedTagIndexes().contains(tagIndex)) {
                return true;
            }
        }

        return false;
    }

    private static int[] getBowFamilyTagIndexes() {
        int[] tagIndexes = bowFamilyTagIndexes;
        if (tagIndexes != null) {
            return tagIndexes;
        }

        synchronized (ArrowDamageBonusSystem.class) {
            if (bowFamilyTagIndexes == null) {
                bowFamilyTagIndexes = new int[] {
                        AssetRegistry.getTagIndex(BOW_FAMILY_TAG),
                        AssetRegistry.getTagIndex(CROSSBOW_FAMILY_TAG)
                };
            }
            return bowFamilyTagIndexes;
        }
    }
}
