package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * One carved digit of a roll's big number, shown to one player only and turned to face them.
 *
 * <p>Each player near a roll gets their own set of tiles, so the digits always read left to right from where they
 * stand: one shared set would read 41 for 14 to anyone behind it. The tiles live a few seconds and are never saved.</p>
 */
public final class NumberTile implements Component<EntityStore> {

    final UUID viewer;
    final Vector3d anchor;
    final int place;
    final int places;
    final String modelAsset;
    final String texture;
    final RollBand band;
    final int life;
    final NumberShow show;
    int age;
    float shownScale = -1.0f;

    NumberTile(@Nonnull NumberShow show, @Nonnull UUID viewer, @Nonnull Vector3d anchor, int place, int places,
               @Nonnull String modelAsset, @Nonnull String texture, @Nonnull RollBand band, int life) {
        this.show = show;
        this.viewer = viewer;
        this.anchor = new Vector3d(anchor);
        this.place = place;
        this.places = places;
        this.modelAsset = modelAsset;
        this.texture = texture;
        this.band = band;
        this.life = life;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        // A tile belongs to one viewer for a few seconds and is never copied.
        return this;
    }
}
