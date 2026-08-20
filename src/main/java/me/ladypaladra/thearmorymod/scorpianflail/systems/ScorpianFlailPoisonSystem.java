// FILE: src/main/java/me/ladypaladra/thearmorymod/scorpianflail/systems/ScorpianFlailPoisonSystem.java
package me.ladypaladra.thearmorymod.scorpianflail.systems;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.ThreadLocalRandom;

public final class ScorpianFlailPoisonSystem extends DamageEventSystem {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String SCORPIAN_FLAIL_ITEM_ID = "ScorpianFlail";

    private static final String POISON_EFFECT_ID = "Poison";
    private static final float POISON_CHANCE = 0.05F;
    private static final float POISON_DURATION_SECONDS = 5.0F;

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
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> chunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Damage damage
    ) {
        if (damage.getAmount() <= 0.0F) {
            return;
        }

        Ref<EntityStore> targetRef = chunk.getReferenceTo(index);
        if (!targetRef.isValid()) {
            return;
        }

        Ref<EntityStore> attackerRef = getAttackerRef(damage);
        if (attackerRef == null || !attackerRef.isValid()) {
            return;
        }

        if (attackerRef.equals(targetRef)) {
            return;
        }

        if (!isPlayer(store, commandBuffer, attackerRef)) {
            return;
        }

        String heldItemId = getHeldItemId(store, commandBuffer, attackerRef);
        if (!isScorpianFlail(heldItemId)) {
            return;
        }

        if (ThreadLocalRandom.current().nextFloat() >= POISON_CHANCE) {
            return;
        }

        applyPoison(targetRef, store, commandBuffer);
    }

    @Nullable
    private static Ref<EntityStore> getAttackerRef(@Nonnull Damage damage) {
        Damage.Source source = damage.getSource();

        if (source instanceof Damage.EntitySource entitySource) {
            return entitySource.getRef();
        }

        return null;
    }

    private static boolean isPlayer(
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> ref
    ) {
        Player player = commandBuffer.getComponent(ref, Player.getComponentType());
        if (player != null) {
            return true;
        }

        return store.getComponent(ref, Player.getComponentType()) != null;
    }

    @Nonnull
    private static String getHeldItemId(
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> attackerRef
    ) {
        ItemStack heldItem = InventoryComponent.getItemInHand(commandBuffer, attackerRef);
        if (heldItem == null) {
            heldItem = InventoryComponent.getItemInHand(store, attackerRef);
        }

        return heldItem != null ? safe(heldItem.getItemId()) : "";
    }

    private static boolean isScorpianFlail(@Nullable String itemIdRaw) {
        String itemId = safe(itemIdRaw);
        if (itemId.isEmpty()) {
            return false;
        }

        // Exact matching keeps a future recolor from inheriting poison by accident.
        // A new flail variant meant to poison must be added here deliberately.
        return itemId.equals(SCORPIAN_FLAIL_ITEM_ID);
    }

    private static void applyPoison(
            @Nonnull Ref<EntityStore> targetRef,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        EffectControllerComponent effectController = store.getComponent(
                targetRef,
                EffectControllerComponent.getComponentType()
        );

        if (effectController == null) {
            return;
        }

        EntityEffect poison = EntityEffect.getAssetMap().getAsset(POISON_EFFECT_ID);
        if (poison == null) {
            LOGGER.atWarning().log("Could not apply poison: EntityEffect %s not found.", POISON_EFFECT_ID);
            return;
        }

        effectController.addEffect(
                targetRef,
                poison,
                POISON_DURATION_SECONDS,
                OverlapBehavior.OVERWRITE,
                commandBuffer
        );
    }

    @Nonnull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
