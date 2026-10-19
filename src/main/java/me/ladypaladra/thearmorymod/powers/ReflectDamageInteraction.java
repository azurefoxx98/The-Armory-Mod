package me.ladypaladra.thearmorymod.powers;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Sends part of a blocked hit back to whoever dealt it.
 *
 * <p>Meant for a shield guard's BlockedInteractions. The engine runs those on every hit the
 * guard blocks, with the attacker as the target entity and the blocked hit in the context. The
 * guard has already scaled the hit's live amount, to nothing for a block from the front, so the
 * reflection is a share of the hit as it was first dealt.</p>
 *
 * <p>A reflected hit is never reflected again. Two players guarding with reflecting shields
 * would otherwise send one hit back and forth between them.</p>
 */
public final class ReflectDamageInteraction extends SimpleInstantInteraction {

    @Nonnull
    public static final BuilderCodec<ReflectDamageInteraction> CODEC = BuilderCodec
            .builder(ReflectDamageInteraction.class, ReflectDamageInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Deals a share of the blocked hit back to its attacker.")
            .append(
                    new KeyedCodec<>("Fraction", Codec.FLOAT),
                    (interaction, value) -> interaction.fraction = value,
                    interaction -> interaction.fraction
            )
            .documentation("Share of the hit's original amount sent back, 0.5 is half.")
            .add()
            .append(
                    new KeyedCodec<>("DamageCause", Codec.STRING),
                    (interaction, value) -> interaction.damageCause = value,
                    interaction -> interaction.damageCause
            )
            .documentation("Damage cause of the reflected hit. A hit of this cause is never reflected.")
            .add()
            .build();

    private float fraction = 0.5F;

    private String damageCause = "TA_Reflected";

    public ReflectDamageInteraction() {
    }

    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nonnull CooldownHandler cooldownHandler
    ) {
        context.getState().state = InteractionState.Finished;

        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        if (commandBuffer == null) {
            return;
        }

        Damage blocked = context.getMetaStore().getIfPresentMetaObject(Interaction.DAMAGE);
        Ref<EntityStore> attacker = context.getMetaStore().getIfPresentMetaObject(Interaction.TARGET_ENTITY);
        Ref<EntityStore> defender = context.getEntity();
        if (blocked == null || attacker == null || !attacker.isValid() || attacker.equals(defender)) {
            return;
        }

        int causeIndex = DamageCause.getAssetMap().getIndex(damageCause);
        if (causeIndex < 0 || blocked.getDamageCauseIndex() == causeIndex) {
            return;
        }

        float amount = blocked.getInitialAmount() * fraction;
        if (!(amount > 0.0F) || !Float.isFinite(amount)) {
            return;
        }

        DamageSystems.executeDamage(attacker, commandBuffer, new Damage(new Damage.EntitySource(defender), causeIndex, amount));
    }
}
