package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * Picks a landed die up with F: anyone may take any die.
 *
 * <p>The die is claimed and its removal scheduled before the item is given. Giving first would change the held item
 * while the chain still runs, which the engine treats as a swap and cancels, leaving the die on the ground as a
 * duplicate (measured on 0.7.4). Claiming in the same instant also stops two players pressing F in one tick from both
 * getting it. Taking either die of a D100 takes the pair.</p>
 */
public final class PickUpDieInteraction extends SimpleInstantInteraction {

    @Nonnull
    public static final BuilderCodec<PickUpDieInteraction> CODEC = BuilderCodec
            .builder(PickUpDieInteraction.class, PickUpDieInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Picks up the landed die being used.")
            .build();

    public PickUpDieInteraction() {
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
        Ref<EntityStore> target = context.getTargetEntity();
        if (commandBuffer == null || target == null || !target.isValid()) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        LandedDie die = commandBuffer.getComponent(target, DiceModule.landedType());
        if (die == null || die.item() == null || !die.claim()) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        Ref<EntityStore> player = context.getEntity();
        PlayerRef taker = commandBuffer.getComponent(player, PlayerRef.getComponentType());
        UUID pair = die.pair();
        if (pair != null) {
            Ref<EntityStore> partner = LandedDieSystems.partner(pair, target);
            LandedDie other = partner != null ? commandBuffer.getComponent(partner, DiceModule.landedType()) : null;
            if (other != null && other.claim()) {
                if (taker != null) {
                    PickUpFlight.launch(partner, player, taker.getUuid(), PickUpFlight.colourOf(other), false,
                            commandBuffer);
                }
                removeNextTick(partner, commandBuffer);
            } else if (partner == null) {
                // The other die lies in an unloaded chunk: it is cleared when that chunk loads.
                DiceModule.ledger().pairCollected(pair);
            }
        }

        TransformComponent at = commandBuffer.getComponent(target, TransformComponent.getComponentType());
        // The die flies into the hand and is heard arriving; without its copy it is heard where it lay.
        boolean flies = taker != null
                && PickUpFlight.launch(target, player, taker.getUuid(), PickUpFlight.colourOf(die), true, commandBuffer);
        removeNextTick(target, commandBuffer);

        var inventory = InventoryComponent.getCombined(commandBuffer, player, InventoryComponent.HOTBAR_STORAGE_BACKPACK);
        SimpleItemContainer.addOrDropItemStack(commandBuffer, player, inventory, new ItemStack(die.item(), 1));
        if (!flies && at != null) {
            DiceShow.pickedUp(at.getPosition(), commandBuffer);
        }
    }

    /**
     * Removes a claimed die on the next tick, as the game's own entity removal does. A die whose light is still
     * fading has an update queued for every player watching it this tick, and removing it now leaves those updates
     * pointing at nothing, which the engine reports as a warning per player (measured on 0.7.4). The claim already
     * stops anyone else taking it in between, and the removal is scheduled before the die is given, so nothing the
     * chain does after can keep it on the ground.
     */
    private static void removeNextTick(@Nonnull Ref<EntityStore> die, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        World world = commandBuffer.getExternalData().getWorld();
        world.execute(() -> {
            if (die.isValid()) {
                world.getEntityStore().getStore().removeEntity(die, RemoveReason.REMOVE);
            }
        });
    }
}
