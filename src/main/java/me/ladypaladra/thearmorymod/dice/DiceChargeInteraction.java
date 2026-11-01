package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.ChargingInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * The game's own charge, followed by the server as it is held, so the die in the hand can show it: its marks light up
 * and motes gather as the throw grows, and at full strength it flashes.
 *
 * <p>Everything else is the game's charge, read from the same fields. The client is sent an ordinary charge, so
 * holding and releasing feel exactly as before. The engine builds its own blocking stance the same way, on top of this
 * charge.</p>
 */
public final class DiceChargeInteraction extends ChargingInteraction {

    @Nonnull
    public static final BuilderCodec<DiceChargeInteraction> CODEC = BuilderCodec
            .builder(DiceChargeInteraction.class, DiceChargeInteraction::new, ChargingInteraction.CODEC)
            .documentation("The game's charge, with the held die lighting up as the throw grows.")
            .build();

    public DiceChargeInteraction() {
    }

    @Override
    protected void tick0(boolean firstRun, float time, @Nonnull InteractionType type, @Nonnull InteractionContext context,
                         @Nonnull CooldownHandler cooldownHandler) {
        super.tick0(firstRun, time, type, context, cooldownHandler);
        // Every tick the button is still down: the time is the server's own count since the charge began.
        if (context.getState().state == InteractionState.NotFinished) {
            DiceCharge.held(context, time);
        }
    }

    @Override
    public void handle(@Nonnull Ref<EntityStore> ref, boolean firstRun, float time, @Nonnull InteractionType type,
                       @Nonnull InteractionContext context) {
        super.handle(ref, firstRun, time, type, context);
        // Released or cancelled: the engine runs this for both, so the hand goes dark either way.
        if (context.getState().state != InteractionState.NotFinished) {
            DiceCharge.ended(context);
        }
    }
}
