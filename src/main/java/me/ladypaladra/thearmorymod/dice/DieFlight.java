package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * A die in flight. It lives only while the die flies: a thrown die is never saved by the engine, so a die caught in
 * flight by a restart is brought back by the ledger instead.
 */
public final class DieFlight implements Component<EntityStore> {

    enum Phase {
        FLYING,
        TOPPLING,
        DONE
    }

    final DieType type;
    final DieShape shape;
    final DieMotion motion;
    final DieMotion.Spin spin = new DieMotion.Spin();
    final ThrowGroup group;
    final int member;
    final int part;

    Phase phase = Phase.FLYING;
    int age;
    int stillTicks;

    /**
     * The die's own rigid body, when the flight is ours and not the engine's: set once the projectile exists.
     */
    RigidFlight rigid;

    /**
     * Where the throw started, the thrower's eye: open air, so a die released inside a wall is drawn back towards it.
     */
    final Vector3d origin = new Vector3d();

    /**
     * Ticks of flight since the last bounce sound, so a die rattling on a corner does not clack every tick.
     */
    int sinceClack;

    /**
     * The creature the die last bounced off, and when, so it is not struck again while still touching it.
     */
    Ref<EntityStore> lastHit;
    int lastHitAge;

    // Set when the die stops: the face it shows and how it lies down.
    DieShape.Face face;
    double restYaw;
    final Quaterniond toppleFrom = new Quaterniond();
    final Quaterniond toppleTo = new Quaterniond();
    final Vector3d toppleStart = new Vector3d();
    double floorY;
    int toppleTick;
    int toppleTicks;

    /**
     * Set once the die has been laid down as a landed die, so its projectile's removal is not taken for a loss.
     */
    boolean handedOver;

    DieFlight(@Nonnull DieType type, @Nonnull DieShape shape, @Nonnull DieMotion motion, @Nonnull ThrowGroup group,
              int member, int part) {
        this.type = type;
        this.shape = shape;
        this.motion = motion;
        this.group = group;
        this.member = member;
        this.part = part;
    }

    @Nonnull
    UUID owner() {
        return group.owner();
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        // A flight is never copied: it belongs to one projectile for as long as it flies.
        return this;
    }
}
