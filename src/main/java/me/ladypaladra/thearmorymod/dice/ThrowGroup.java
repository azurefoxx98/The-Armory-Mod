package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * One throw: a single die, a D100's pair, or a crouched handful of up to five. Its dice land one by one, and the throw
 * is read and shown once the last has landed or been lost, so a handful gives one chat line with every value and the
 * total, and a D100 shows one number for its pair.
 *
 * <p>A member is one item thrown. A D100 member has two parts, its tens and its units die, which land and are lost as
 * one: the item is a pair, so a pair missing a die cannot be picked up, and the whole item goes back to its owner.</p>
 */
public final class ThrowGroup {

    private final DieType type;
    private final UUID owner;
    private final String ownerName;
    private final UUID[] ledgerIds;
    private final UUID[] pairIds;
    private final DieShape.Face[][] faces;
    private final Vector3d[][] positions;
    @SuppressWarnings("unchecked")
    private final Ref<EntityStore>[][] landed;
    private final boolean[] lost;
    private boolean done;

    /**
     * The rigid bodies of every die thrown together, so they knock against one another instead of passing through.
     * Only the world thread that flies them touches it.
     */
    final List<RigidFlight> bodies = new ArrayList<>();

    @SuppressWarnings("unchecked")
    ThrowGroup(@Nonnull DieType type, @Nonnull UUID owner, @Nonnull String ownerName, int members) {
        this.type = type;
        this.owner = owner;
        this.ownerName = ownerName;
        int parts = type.shapeKeys().size();
        this.ledgerIds = new UUID[members];
        this.pairIds = new UUID[members];
        this.faces = new DieShape.Face[members][parts];
        this.positions = new Vector3d[members][parts];
        this.landed = new Ref[members][parts];
        this.lost = new boolean[members];
        for (int i = 0; i < members; i++) {
            ledgerIds[i] = UUID.randomUUID();
            pairIds[i] = parts > 1 ? UUID.randomUUID() : null;
        }
    }

    @Nonnull
    public DieType type() {
        return type;
    }

    @Nonnull
    public UUID owner() {
        return owner;
    }

    @Nonnull
    public String ownerName() {
        return ownerName;
    }

    public int members() {
        return lost.length;
    }

    @Nonnull
    UUID ledgerId(int member) {
        return ledgerIds[member];
    }

    @Nullable
    UUID pairId(int member) {
        return pairIds[member];
    }

    boolean isLost(int member) {
        return lost[member];
    }

    /**
     * Records a part landed. Returns true when this completes its member, so the ledger can let the item go.
     */
    boolean land(int member, int part, @Nonnull DieShape.Face face, @Nonnull Vector3d position,
                 @Nonnull Ref<EntityStore> ref) {
        faces[member][part] = face;
        positions[member][part] = new Vector3d(position);
        landed[member][part] = ref;
        return memberComplete(member);
    }

    /**
     * Records a member lost, and returns the landed dice of its other part, which must leave with it.
     */
    @Nonnull
    List<Ref<EntityStore>> lose(int member) {
        lost[member] = true;
        List<Ref<EntityStore>> orphans = new ArrayList<>();
        for (Ref<EntityStore> ref : landed[member]) {
            if (ref != null) {
                orphans.add(ref);
            }
        }
        Arrays.fill(landed[member], null);
        return orphans;
    }

    /**
     * True once, the moment every member has landed or been lost: the throw is ready to be read.
     */
    boolean claimFinished() {
        if (done) {
            return false;
        }
        for (int m = 0; m < lost.length; m++) {
            if (!lost[m] && !memberComplete(m)) {
                return false;
            }
        }
        done = true;
        return true;
    }

    /**
     * The value each surviving member rolled, in throw order.
     */
    @Nonnull
    List<Roll> rolls() {
        List<Roll> out = new ArrayList<>();
        for (int m = 0; m < lost.length; m++) {
            if (lost[m]) {
                continue;
            }
            List<String> labels = new ArrayList<>();
            List<Ref<EntityStore>> dice = new ArrayList<>();
            Vector3d middle = new Vector3d();
            for (int p = 0; p < faces[m].length; p++) {
                labels.add(faces[m][p].label());
                middle.add(positions[m][p]);
                if (landed[m][p] != null) {
                    dice.add(landed[m][p]);
                }
            }
            middle.div(faces[m].length);
            out.add(new Roll(type.read(labels), middle, List.copyOf(dice)));
        }
        return out;
    }

    private boolean memberComplete(int member) {
        for (DieShape.Face f : faces[member]) {
            if (f == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * One item's result, where its number rises (above the die, or between a D100's two dice), and the landed dice
     * that show it, which the extremes light, lift or darken.
     */
    record Roll(int value, @Nonnull Vector3d at, @Nonnull List<Ref<EntityStore>> dice) {
    }
}
