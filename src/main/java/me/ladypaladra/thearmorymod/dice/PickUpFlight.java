package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A picked-up die flying into its new owner's hand: a copy of the landed die that rises along a short arc to the hand,
 * turning and shrinking a little, and ends in a spark of the die's colour. The copy carries the die: it goes into the
 * inventory in the same tick as the spark and the pickup sound, the moment the copy leaves the screen, so a player
 * never holds a die that is still in the air in front of them.
 *
 * <p>Nothing can touch the copy, and it is never saved, so the die it carries is written in the dice ledger until it
 * arrives. A taker who leaves mid-flight, a copy the world removes, or a server stopping in between makes the die
 * owed, and the taker gets it back on the spot or at their next login.</p>
 */
public final class PickUpFlight implements Component<EntityStore> {

    /**
     * Ticks the flight takes: 0.3 s at 30 a second, quick enough to feel like a catch.
     */
    static final int TICKS = 9;

    /**
     * Blocks the arc rises over the straight line at its middle, and the turns the die makes on the way.
     */
    static final double ARC = 0.35;
    static final double TURNS = 1.25;

    /**
     * The size the die ends at, from its own, so it reads as going into the hand rather than hitting it.
     */
    static final float END_SCALE = 0.55f;

    static final String SPARK = "Armory_Dice_PickUp_Spark_";

    final UUID player;
    final Vector3d start;
    final float startYaw;
    final String modelAsset;
    final float scale;
    final String colour;
    /**
     * Whether its arrival plays the pickup sound: a D100's pair flies as two copies and is heard once.
     */
    final boolean heard;
    /**
     * The item the copy delivers and its line in the ledger, or null for a copy that only shows the way: a D100's
     * second die, whose pair arrives as one item on the first.
     */
    @Nullable
    final String item;
    @Nullable
    final UUID delivery;
    boolean delivered;
    int age;
    float shownScale = -1.0f;

    private PickUpFlight(@Nonnull UUID player, @Nonnull Vector3d start, float startYaw, @Nonnull String modelAsset,
                         float scale, @Nonnull String colour, boolean heard, @Nullable String item,
                         @Nullable UUID delivery) {
        this.heard = heard;
        this.item = item;
        this.delivery = delivery;
        this.player = player;
        this.start = new Vector3d(start);
        this.startYaw = startYaw;
        this.modelAsset = modelAsset;
        this.scale = scale;
        this.colour = colour;
    }

    /**
     * Sends a copy of the landed die {@code die} flying to {@code player}'s hand, carrying {@code item} when it is not
     * null: the ledger holds it as in flight until the copy hands it over.
     *
     * @return whether the copy is on its way; if not, the caller gives the die and plays the pickup on the spot
     */
    static boolean launch(@Nonnull Ref<EntityStore> die, @Nonnull Ref<EntityStore> playerRef, @Nonnull UUID player,
                          @Nonnull String colour, boolean heard, @Nullable String item,
                          @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        TransformComponent at = commandBuffer.getComponent(die, TransformComponent.getComponentType());
        ModelComponent drawn = commandBuffer.getComponent(die, ModelComponent.getComponentType());
        if (at == null || drawn == null || !playerRef.isValid()) {
            return false;
        }
        Model model = drawn.getModel();
        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(TransformComponent.getComponentType(),
                new TransformComponent(new Vector3d(at.getPosition()), new Rotation3f(at.getRotation())));
        holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
        holder.addComponent(NetworkId.getComponentType(),
                new NetworkId(commandBuffer.getExternalData().takeNextNetworkId()));
        holder.ensureComponent(Intangible.getComponentType());
        holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
        UUID delivery = item != null ? UUID.randomUUID() : null;
        if (delivery != null) {
            DiceModule.ledger().flying(delivery, player, item);
        }
        holder.addComponent(DiceModule.pickUpType(), new PickUpFlight(player, at.getPosition(), at.getRotation().yaw(),
                model.getModelAssetId(), model.getScale(), colour, heard, item, delivery));
        commandBuffer.addEntity(holder, AddReason.SPAWN);
        return true;
    }

    /**
     * Puts the carried die into the taker's inventory, or on the ground at their feet when it is full, and strikes it
     * off the ledger. Once only.
     */
    private void deliver(@Nonnull Ref<EntityStore> player, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (delivered || item == null) {
            return;
        }
        delivered = true;
        var inventory = InventoryComponent.getCombined(commandBuffer, player, InventoryComponent.HOTBAR_STORAGE_BACKPACK);
        SimpleItemContainer.addOrDropItemStack(commandBuffer, player, inventory, new ItemStack(item, 1));
        if (delivery != null) {
            DiceModule.ledger().landed(delivery);
        }
    }

    /**
     * The carried die did not arrive: the taker is owed it.
     */
    private void undelivered() {
        if (!delivered && delivery != null) {
            delivered = true;
            DiceModule.ledger().lost(delivery);
        }
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        // A flight lasts a few ticks and is never copied.
        return this;
    }

    /**
     * Moves each flying copy along its arc to the hand, and ends it there in a spark.
     */
    public static final class Fly extends EntityTickingSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Query.and(DiceModule.pickUpType(), TransformComponent.getComponentType());
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                         @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            PickUpFlight flight = chunk.getComponent(index, DiceModule.pickUpType());
            TransformComponent transform = chunk.getComponent(index, TransformComponent.getComponentType());
            if (flight == null || transform == null) {
                return;
            }
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            Ref<EntityStore> player = store.getExternalData().getRefFromUUID(flight.player);
            if (player == null || !player.isValid()) {
                flight.undelivered();
                commandBuffer.tryRemoveEntity(ref, RemoveReason.REMOVE);
                return;
            }
            flight.age++;
            double t = Math.min(1.0, (double) flight.age / TICKS);
            Vector3d hand = DiceCharge.hand(player, commandBuffer);
            if (t >= 1.0) {
                flight.deliver(player, commandBuffer);
                DiceShow.particles(SPARK + flight.colour, hand, 1.0f, commandBuffer);
                if (flight.heard) {
                    DiceShow.pickedUp(hand, commandBuffer);
                }
                commandBuffer.tryRemoveEntity(ref, RemoveReason.REMOVE);
                return;
            }
            Vector3d at = place(flight.start, hand, t, new Vector3d());
            transform.getPosition().set(at);
            transform.getRotation().set(0.0f, (float) (flight.startYaw + TURNS * 2.0 * Math.PI * ease(t)), 0.0f);
            float size = size(flight.scale, t);
            if (Math.abs(size - flight.shownScale) > 1.0E-3) {
                Model model = scaled(flight.modelAsset, size);
                if (model != null) {
                    commandBuffer.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(model));
                }
                flight.shownScale = size;
            }
        }
    }

    /**
     * A copy the world removes before it arrives (its chunk unloaded, the server stopping) leaves its die owed.
     */
    public static final class Ended extends RefSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return DiceModule.pickUpType();
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                                  @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                                   @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            PickUpFlight flight = store.getComponent(ref, DiceModule.pickUpType());
            if (flight != null) {
                flight.undelivered();
            }
        }
    }

    /**
     * Where the copy is at {@code t} from 0 to 1: slow off the ground, fast into the hand, over a short arc.
     */
    @Nonnull
    static Vector3d place(@Nonnull Vector3d start, @Nonnull Vector3d hand, double t, @Nonnull Vector3d dest) {
        double e = ease(t);
        return dest.set(start).lerp(hand, e).add(0.0, ARC * Math.sin(Math.PI * e), 0.0);
    }

    static double ease(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * Its size at {@code t}: whole for the first half, then shrinking to {@link #END_SCALE}, rounded so the model is
     * only rebuilt while it actually changes.
     */
    static float size(float scale, double t) {
        double shrink = t < 0.5 ? 1.0 : 1.0 - (1.0 - END_SCALE) * (t - 0.5) / 0.5;
        return Math.round((float) (scale * shrink) * 50.0f) / 50.0f;
    }

    @Nullable
    private static Model scaled(@Nonnull String modelAsset, float size) {
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(modelAsset);
        return asset != null ? Model.createScaledModel(asset, size) : null;
    }

    /**
     * The colour a landed die's spark is drawn in.
     */
    @Nonnull
    static String colourOf(@Nullable LandedDie die) {
        DiceCatalog catalog = DiceModule.catalog();
        DieShape shape = die != null && die.shape() != null && catalog != null ? catalog.shapes().get(die.shape()) : null;
        return shape != null ? RollBand.capitalise(shape.colour()) : "White";
    }
}
