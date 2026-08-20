package me.ladypaladra.thearmorymod.alteration;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Right click feed for the Alteration Table. The kit item carries this as its
 * Secondary interaction, so physical right click banks a charge while the F key
 * stays free to open the bench. On any block that is not an alteration table the
 * interaction fails, which hands control to the Failed branch of the interaction
 * asset and places the kit as a decorative block instead. Both halves of that
 * behaviour are deliberate, the kit is fuel when aimed at a table and ordinary
 * furniture everywhere else.
 */
public final class FeedAlterationTableInteraction extends SimpleBlockInteraction {

    @Nonnull
    public static final BuilderCodec<FeedAlterationTableInteraction> CODEC;

    public FeedAlterationTableInteraction() {
    }

    @Override
    protected void interactWithBlock(
            @Nonnull World world,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nullable ItemStack itemInHand,
            @Nonnull Vector3i targetBlock,
            @Nonnull CooldownHandler cooldownHandler
    ) {
        BlockType blockType = world.getBlockType(targetBlock.x, targetBlock.y, targetBlock.z);

        // Only alteration tables accept a feed. Anything else fails the interaction so
        // the chain falls through to the Failed branch in Alteration_Kit_Feed.json, which
        // places the kit as a block. Failing here is what makes the kit both fuel and a
        // deco piece, the same way vanilla Block_Secondary tries UseBlock first and falls
        // back to PlaceBlock. Returning without setting the state would finish the chain
        // and swallow the placement.
        if (!isAlterationTable(blockType)) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        Ref<EntityStore> playerRef = context.getEntity();
        Store<EntityStore> entityStore = playerRef.getStore();

        // Right click banks one kit into the table's fuel stock, depositKit consumes the
        // kit and handles every refusal path internally.
        AlterationTransaction.depositKit(playerRef, entityStore, world, targetBlock);
    }

    @Override
    protected void simulateInteractWithBlock(
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nullable ItemStack itemInHand,
            @Nonnull World world,
            @Nonnull Vector3i targetBlock
    ) {
        // Mirror ChangeBlockInteraction, which fails the client prediction when the
        // target block has no entry to act on. Failing here means the client predicts
        // no use animation when the kit is aimed at anything but an alteration table.
        if (!isAlterationTable(world.getBlockType(targetBlock.x, targetBlock.y, targetBlock.z))) {
            context.getState().state = InteractionState.Failed;
        }
    }

    /**
     * Whether the block is one of the mod's alteration tables. The mod owns ten table
     * blocks whose item ids all share the same prefix, so a prefix match on the block's
     * item id covers the base table and every color variant without listing them.
     */
    private static boolean isAlterationTable(@Nullable BlockType blockType) {
        if (blockType == null || blockType == BlockType.EMPTY || blockType == BlockType.UNKNOWN) {
            return false;
        }

        Item item = blockType.getItem();
        if (item == null) {
            return false;
        }

        String itemId = item.getId();
        return itemId != null && itemId.startsWith(AlterationConfig.TABLE_ITEM_ID_PREFIX);
    }

    static {
        CODEC = BuilderCodec
                .builder(
                        FeedAlterationTableInteraction.class,
                        FeedAlterationTableInteraction::new,
                        SimpleBlockInteraction.CODEC
                )
                .documentation("Feeds a held Alteration Kit into a targeted Alteration Table to bank a charge.")
                .build();
    }
}
