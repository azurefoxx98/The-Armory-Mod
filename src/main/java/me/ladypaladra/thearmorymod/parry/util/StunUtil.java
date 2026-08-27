package me.ladypaladra.thearmorymod.parry.util;

import com.hypixel.hytale.builtin.npccombatactionevaluator.evaluator.CombatActionEvaluator;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.parry.ParryModule;
import me.ladypaladra.thearmorymod.parry.components.StunComponent;

public final class StunUtil {

    private static final String STUN_EFFECT_ID = "TA_Entity_Stunned";

    private StunUtil() {
    }

    public static void applyStun(Ref<EntityStore> entityRef,
                                 Store<EntityStore> store,
                                 CommandBuffer<EntityStore> commandBuffer,
                                 float durationSeconds) {
        if (entityRef == null || !entityRef.isValid()) {
            return;
        }

        ComponentType<EntityStore, StunComponent> stunComponentType = ParryModule.getStunComponentType();
        StunComponent existing = store.getComponent(entityRef, stunComponentType);
        if (existing != null) {
            existing.setTimeRemaining(Math.max(existing.getTimeRemaining(), durationSeconds));
        } else {
            commandBuffer.addComponent(entityRef, stunComponentType, new StunComponent(durationSeconds));
        }

        AnimationUtils.playAnimation(entityRef, AnimationSlot.Action, "Hurt", true, store);
        clearCombatState(entityRef, commandBuffer);
        applyStunEffect(entityRef, store, commandBuffer);
    }

    public static void enforceStun(Ref<EntityStore> entityRef,
                                   Store<EntityStore> store,
                                   CommandBuffer<EntityStore> commandBuffer) {
        if (entityRef == null || !entityRef.isValid()) {
            return;
        }

        clearCombatState(entityRef, commandBuffer);
        applyStunEffect(entityRef, store, commandBuffer);
    }

    public static void removeStunEffect(Ref<EntityStore> entityRef,
                                        Store<EntityStore> store,
                                        CommandBuffer<EntityStore> commandBuffer) {
        if (entityRef == null || !entityRef.isValid()) {
            return;
        }

        EffectControllerComponent effectController = store.getComponent(entityRef, EffectControllerComponent.getComponentType());
        if (effectController == null) {
            return;
        }

        EntityEffect effect = EntityEffect.getAssetMap().getAsset(STUN_EFFECT_ID);
        if (effect == null) {
            return;
        }

        int effectIndex = EntityEffect.getAssetMap().getIndex(effect.getId());
        if (effectIndex < 0) {
            return;
        }

        effectController.removeEffect(entityRef, effectIndex, commandBuffer);
    }

    private static void applyStunEffect(Ref<EntityStore> entityRef,
                                        Store<EntityStore> store,
                                        CommandBuffer<EntityStore> commandBuffer) {
        EffectControllerComponent effectController = store.getComponent(entityRef, EffectControllerComponent.getComponentType());
        if (effectController == null) {
            return;
        }

        EntityEffect effect = EntityEffect.getAssetMap().getAsset(STUN_EFFECT_ID);
        if (effect == null) return;

        // Let the effect asset carry its own duration and overlap behaviour. The engine treats
        // both as defaults that any explicit argument replaces, so passing them here would make
        // the values in the asset unreachable while still looking like they governed. How long
        // the target stays stunned is a separate question, answered by the stun component's
        // countdown, and this system re-applies the effect on every tick of it.
        effectController.addEffect(entityRef, effect, commandBuffer);
    }

    private static void clearCombatState(Ref<EntityStore> entityRef,
                                         CommandBuffer<EntityStore> commandBuffer) {
        InteractionManager interactionManager = commandBuffer.getComponent(
                entityRef,
                InteractionModule.get().getInteractionManagerComponent()
        );
        if (interactionManager != null) {
            interactionManager.clear();
        }

        CombatActionEvaluator cae = commandBuffer.getComponent(entityRef, CombatActionEvaluator.getComponentType());
        if (cae != null) {
            cae.completeCurrentAction(true, true);
            cae.clearPrimaryTarget();
        }
    }
}
