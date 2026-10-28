package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A landed die's own moment of show, tick by tick: the light it casts, and for the extremes how it moves and whether
 * its marks are lit. Every roll awakens in the die's colour. The Avatar's Favour casts a long gold light and, from a
 * D20 up, lifts the die half a block to turn once in the pillar and set it down. The Veil turns the light crimson and
 * gutters it out, and from a D20 up the die shudders while its marks go dark until the rift closes, then they flicker
 * back.
 *
 * <p>Everything here is a plain function of the age, so the whole timeline can be checked without a server. Ticks are
 * the world's, 30 a second. The extremes' times follow the particle shows they play with, and must move with them:
 * the Favour's pillar falls at 0.45 s, and the Veil's rift closes at 0.4 s plus 2.4 s for each unit of the tier's
 * length (1 for a D20, 1.5 for a D100).</p>
 *
 * <p>Never saved. A die saved in the middle of a lift or a shudder comes back at its resting place, which the landed
 * die keeps for that, and a texture swap lives only in the live model, so a reloaded die always has its lit mark.</p>
 */
public final class DieGlow implements Component<EntityStore> {

    enum Style {
        AWAKEN,
        FAVOUR,
        VEIL
    }

    /**
     * Ticks the awakening's light lasts, and the light's radius at its brightest, in blocks.
     */
    static final int LIFE_TICKS = 45;
    static final int PEAK_RADIUS = 5;

    /**
     * The extremes' light: the Favour's gold and the Veil's crimson, the colours their particles are drawn in.
     */
    static final DieShape.Rgb GOLD = new DieShape.Rgb(255, 201, 74);
    static final DieShape.Rgb CRIMSON = new DieShape.Rgb(255, 36, 64);

    /**
     * The Favour's lift: when it starts (as the pillar falls), and how many ticks it rises, hangs turning and sets
     * down, each multiplied by the tier's length. Blocks it rises.
     */
    static final int LIFT_START = 15;
    static final int LIFT_RISE = 24;
    static final int LIFT_HANG = 51;
    static final int LIFT_FALL = 18;

    /**
     * The Veil: the tick its marks go dark, how long the shudder lasts, and the tick the marks come back for each
     * tier, 0.6 s after its rift closes.
     */
    static final int VEIL_DARK = 9;
    static final int SHUDDER_START = 6;
    static final int SHUDDER_TICKS = 48;
    static final int VEIL_RELIGHT_MAJOR = 102;
    static final int VEIL_RELIGHT_GRAND = 138;
    static final int RELIGHT_FLICKER = 9;

    final DieShape.Rgb dieColour;
    Style style = Style.AWAKEN;
    RollBand.Tier tier = RollBand.Tier.MINOR;
    int age;

    /**
     * Set when an extreme takes the die over: where it rests and how it is turned, so every move ends exactly there,
     * and the two textures its marks are drawn lit and dark with.
     */
    @Nullable
    Vector3d rest;
    float restYaw;
    @Nullable
    String texture;
    @Nullable
    String darkTexture;

    /**
     * Whether the live model now wears the dark texture.
     */
    boolean shownDark;

    DieGlow(@Nonnull DieShape.Rgb dieColour) {
        this.dieColour = dieColour;
    }

    /**
     * Starts an extreme's show from now, from where the die lies.
     */
    void begin(@Nonnull Style style, @Nonnull RollBand.Tier tier, @Nonnull Vector3d rest, float restYaw,
               @Nonnull String texture, @Nonnull String darkTexture) {
        this.style = style;
        this.tier = tier;
        this.age = 0;
        this.rest = new Vector3d(rest);
        this.restYaw = restYaw;
        this.texture = texture;
        this.darkTexture = darkTexture;
    }

    /**
     * Ticks the whole show lasts. Once it is over the die lies still, lit, at its resting place.
     */
    int life() {
        return switch (style) {
            case AWAKEN -> LIFE_TICKS;
            case FAVOUR -> switch (tier) {
                case MINOR -> 75;
                case MAJOR -> 120;
                case GRAND -> 180;
            };
            case VEIL -> switch (tier) {
                case MINOR -> LIFE_TICKS;
                case MAJOR -> VEIL_RELIGHT_MAJOR + RELIGHT_FLICKER + 6;
                case GRAND -> VEIL_RELIGHT_GRAND + RELIGHT_FLICKER + 6;
            };
        };
    }

    boolean over() {
        return age >= life();
    }

    /**
     * The colour the die casts now.
     */
    @Nonnull
    DieShape.Rgb colour() {
        return switch (style) {
            case AWAKEN -> dieColour;
            case FAVOUR -> GOLD;
            // The Void takes the light, and when the marks come back they come back in the die's own colour.
            case VEIL -> age < relightTick() ? CRIMSON : dieColour;
        };
    }

    /**
     * How bright the light is now, from 0 to 1.
     */
    double brightness() {
        int life = life();
        if (age >= life) {
            return 0.0;
        }
        return switch (style) {
            case AWAKEN -> riseThenFade(age, 4, life);
            // The gathering light builds until the pillar falls, holds while it stands, then fades with it.
            case FAVOUR -> age < 14 ? 0.35 + 0.65 * (age / 14.0) : age < life * 3 / 4 ? 1.0
                    : fade(age - life * 3 / 4, life - life * 3 / 4);
            case VEIL -> veilBrightness();
        };
    }

    /**
     * The light's reach now, in blocks: the extremes reach further.
     */
    int radius() {
        double peak = switch (style) {
            case AWAKEN -> PEAK_RADIUS;
            case FAVOUR -> switch (tier) {
                case MINOR -> PEAK_RADIUS * 1.4;
                case MAJOR -> PEAK_RADIUS * 2.0;
                case GRAND -> PEAK_RADIUS * 2.6;
            };
            case VEIL -> PEAK_RADIUS * 0.9;
        };
        return Math.max(1, (int) Math.round(peak * Math.min(1.0, 0.4 + 0.6 * brightness())));
    }

    /**
     * Blocks the die stands over its resting place now: the Favour's lift, from a D20 up.
     */
    double lift() {
        if (style != Style.FAVOUR || tier == RollBand.Tier.MINOR) {
            return 0.0;
        }
        double k = length();
        double height = tier == RollBand.Tier.GRAND ? 0.75 : 0.5;
        double t = age - LIFT_START;
        double rise = LIFT_RISE * k;
        double hang = LIFT_HANG * k;
        double fall = LIFT_FALL * k;
        if (t <= 0 || t >= rise + hang + fall) {
            return 0.0;
        }
        if (t < rise) {
            return height * smooth(t / rise);
        }
        // A slow breath while it hangs in the light.
        double bob = 0.04 * Math.sin((t - rise) / hang * Math.PI * 2.0);
        if (t < rise + hang) {
            return height + bob;
        }
        return height * (1.0 - smooth((t - rise - hang) / fall));
    }

    /**
     * Radians the die is turned from its resting yaw now: one slow full turn in the Favour's light, the Veil's
     * shudder. Both end at 0, so the die always settles facing as it landed.
     */
    double turn() {
        if (tier == RollBand.Tier.MINOR) {
            return 0.0;
        }
        if (style == Style.FAVOUR) {
            double k = length();
            double span = (LIFT_RISE + LIFT_HANG + LIFT_FALL) * k;
            double t = (age - LIFT_START) / span;
            if (t <= 0.0 || t >= 1.0) {
                return 0.0;
            }
            return Math.PI * 2.0 * smooth(t);
        }
        if (style == Style.VEIL) {
            double t = age - SHUDDER_START;
            if (t <= 0 || t >= SHUDDER_TICKS) {
                return 0.0;
            }
            double strength = tier == RollBand.Tier.GRAND ? 0.16 : 0.1;
            double fade = 1.0 - t / SHUDDER_TICKS;
            return strength * fade * Math.sin(t * 1.9) * Math.sin(t * 0.53 + 1.0);
        }
        return 0.0;
    }

    /**
     * Whether the die's marks are dark now: the Veil from a D20 up puts them out until its rift has closed, then they
     * flicker back on.
     */
    boolean marksDark() {
        if (style != Style.VEIL || tier == RollBand.Tier.MINOR || age < VEIL_DARK) {
            return false;
        }
        int relight = relightTick();
        if (age < relight) {
            return true;
        }
        int since = age - relight;
        return since < RELIGHT_FLICKER && (since / 3) % 2 == 1;
    }

    /**
     * Whether this show moves the die or changes its marks, and so must put it back exactly when it ends.
     */
    boolean movesTheDie() {
        return tier != RollBand.Tier.MINOR && (style == Style.FAVOUR || style == Style.VEIL);
    }

    private int relightTick() {
        return tier == RollBand.Tier.GRAND ? VEIL_RELIGHT_GRAND : VEIL_RELIGHT_MAJOR;
    }

    private double veilBrightness() {
        int relight = relightTick();
        if (tier != RollBand.Tier.MINOR && age >= relight) {
            // The marks coming back cast a little of the die's own light.
            return 0.45 * fade(age - relight, life() - relight);
        }
        // Crimson that sputters and dies within the first second and a half: the Void takes the light.
        int gutter = 42;
        if (age >= gutter) {
            return 0.0;
        }
        double flicker = (age * 7) % 5 < 3 ? 1.0 : 0.3;
        return riseThenFade(age, 3, gutter) * flicker;
    }

    private double length() {
        return tier == RollBand.Tier.GRAND ? 1.5 : 1.0;
    }

    private static double riseThenFade(int age, int rise, int life) {
        if (age < rise) {
            return (age + 1.0) / rise;
        }
        return fade(age - rise, life - rise);
    }

    private static double fade(int age, int span) {
        double t = Math.min(1.0, (double) age / Math.max(1, span));
        return (1.0 - t) * (1.0 - t);
    }

    private static double smooth(double t) {
        double c = Math.max(0.0, Math.min(1.0, t));
        return c * c * (3.0 - 2.0 * c);
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        DieGlow copy = new DieGlow(dieColour);
        copy.style = style;
        copy.tier = tier;
        copy.age = age;
        copy.rest = rest != null ? new Vector3d(rest) : null;
        copy.restYaw = restYaw;
        copy.texture = texture;
        copy.darkTexture = darkTexture;
        copy.shownDark = shownDark;
        return copy;
    }
}
