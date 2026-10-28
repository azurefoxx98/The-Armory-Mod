package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A die lying where it landed. It is saved with its chunk, so it stays through unloads and restarts until someone
 * picks it up, like a die on a table.
 *
 * <p>The save id is part of every world that holds a landed die: it must never change.</p>
 */
public final class LandedDie implements Component<EntityStore> {

    @Nonnull
    public static final BuilderCodec<LandedDie> CODEC = BuilderCodec.builder(LandedDie.class, LandedDie::new)
            .append(new KeyedCodec<>("Item", Codec.STRING), (d, v) -> d.item = v, d -> d.item)
            .add()
            .append(new KeyedCodec<>("Shape", Codec.STRING), (d, v) -> d.shape = v, d -> d.shape)
            .add()
            .append(new KeyedCodec<>("Face", Codec.INTEGER), (d, v) -> d.face = v, d -> d.face)
            .add()
            .append(new KeyedCodec<>("Owner", Codec.STRING), (d, v) -> d.owner = v, d -> d.owner)
            .add()
            .append(new KeyedCodec<>("Pair", Codec.STRING), (d, v) -> d.pair = v, d -> d.pair)
            .add()
            .append(new KeyedCodec<>("RestY", Codec.DOUBLE), (d, v) -> d.restY = v, d -> d.restY)
            .add()
            .append(new KeyedCodec<>("RestYaw", Codec.DOUBLE), (d, v) -> d.restYaw = v, d -> d.restYaw)
            .add()
            .build();

    private String item;
    private String shape;
    private int face;
    private String owner;
    @Nullable
    private String pair;

    /**
     * Where the die came to rest: its height and its turn. The Favour lifts and turns a die and the Veil shakes it,
     * so a world saved in the middle of one would keep the die floating or askew. It is put back here when it loads.
     */
    private double restY = Double.NaN;
    private double restYaw = Double.NaN;

    /**
     * Set the instant a player takes the die, so a second F in the same tick finds it gone. Never saved: a die
     * claimed is removed in the same tick.
     */
    private boolean claimed;

    public LandedDie() {
    }

    LandedDie(@Nonnull String item, @Nonnull String shape, int face, @Nonnull UUID owner, @Nullable UUID pair,
              double restY, double restYaw) {
        this.item = item;
        this.shape = shape;
        this.face = face;
        this.owner = owner.toString();
        this.pair = pair != null ? pair.toString() : null;
        this.restY = restY;
        this.restYaw = restYaw;
    }

    /**
     * The height the die rests at, or NaN when it was saved without one.
     */
    public double restY() {
        return restY;
    }

    /**
     * The yaw the die rests at, in radians, or NaN when it was saved without one.
     */
    public double restYaw() {
        return restYaw;
    }

    @Nullable
    public String item() {
        return item;
    }

    @Nullable
    public String shape() {
        return shape;
    }

    public int face() {
        return face;
    }

    @Nullable
    public UUID pair() {
        return parse(pair);
    }

    @Nullable
    public UUID owner() {
        return parse(owner);
    }

    /**
     * Claims the die for one pickup. Returns false when it was already taken.
     */
    boolean claim() {
        if (claimed) {
            return false;
        }
        claimed = true;
        return true;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        LandedDie copy = new LandedDie();
        copy.item = item;
        copy.shape = shape;
        copy.face = face;
        copy.owner = owner;
        copy.pair = pair;
        copy.restY = restY;
        copy.restYaw = restYaw;
        copy.claimed = claimed;
        return copy;
    }

    @Nullable
    private static UUID parse(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
