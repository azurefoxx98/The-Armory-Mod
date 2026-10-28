package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The Runebinder's dice: seven dice thrown with right click, tumbling freely, settling on a face, showing the result in
 * chat and as a big carved number, and lying where they land until someone picks them up with F.
 *
 * <p>Everything a die does is cosmetic. Nothing here changes combat, movement or loot.</p>
 */
public final class DiceModule {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * The motion every die flies with. The free tumble turns a die on all three axes. The flat spin is the fallback
     * for a client that does not draw an entity's roll, and swapping it in changes nothing else.
     */
    static final DieMotion MOTION = new TumbleMotion();

    private static ComponentType<EntityStore, DieFlight> flightType;
    private static ComponentType<EntityStore, LandedDie> landedType;
    private static ComponentType<EntityStore, NumberTile> tileType;
    private static ComponentType<EntityStore, DieGlow> glowType;

    @Nullable
    private static DiceCatalog catalog;
    @Nullable
    private static DiceLedger ledger;

    private DiceModule() {
    }

    public static void register(@Nonnull JavaPlugin plugin) {
        // Interaction types must exist before any asset naming them is decoded.
        plugin.getCodecRegistry(Interaction.CODEC).register(
                "ArmoryThrowDie",
                ThrowDieInteraction.class,
                ThrowDieInteraction.CODEC
        );
        plugin.getCodecRegistry(Interaction.CODEC).register(
                "ArmoryPickUpDie",
                PickUpDieInteraction.class,
                PickUpDieInteraction.CODEC
        );

        ComponentRegistryProxy<EntityStore> registry = plugin.getEntityStoreRegistry();
        flightType = registry.registerComponent(DieFlight.class, () -> {
            throw new UnsupportedOperationException("A die's flight is made by its throw");
        });
        landedType = registry.registerComponent(LandedDie.class, "ArmoryLandedDie", LandedDie.CODEC);
        tileType = registry.registerComponent(NumberTile.class, () -> {
            throw new UnsupportedOperationException("A number tile is made by its roll");
        });
        glowType = registry.registerComponent(DieGlow.class, () -> {
            throw new UnsupportedOperationException("A die's glow is made by its landing");
        });

        registry.registerSystem(new DieFlightSystems.Turn());
        registry.registerSystem(new DieFlightSystems.Lost());
        registry.registerSystem(new LandedDieSystems.Loaded());
        registry.registerSystem(new LandedDieSystems.Glow());
        registry.registerSystem(new NumberTileSystems.Animate());
        registry.registerSystem(new NumberTileSystems.OnlyItsViewer());
        registry.registerSystem(new DiceReturnSystem());

        try {
            catalog = DiceCatalog.load();
        } catch (Exception e) {
            // A server must never fail to boot over dice. Without their data they cannot be thrown, and say so.
            catalog = null;
            LOGGER.atSevere().withCause(e).log("The dice data could not be read; dice cannot be thrown");
        }
        ledger = DiceLedger.open(plugin.getDataDirectory().resolve("dice-ledger.json"));
    }

    /**
     * Resolves what needs loaded assets: the particles and sounds the shows name.
     */
    public static void start() {
        DiceShow.resolve();
    }

    public static void shutdown() {
        if (ledger != null) {
            ledger.close();
        }
    }

    @Nullable
    static DiceCatalog catalog() {
        return catalog;
    }

    @Nonnull
    static DiceLedger ledger() {
        if (ledger == null) {
            throw new IllegalStateException("The dice ledger has not been opened yet.");
        }
        return ledger;
    }

    @Nonnull
    static ComponentType<EntityStore, DieFlight> flightType() {
        return require(flightType, "DieFlight");
    }

    @Nonnull
    static ComponentType<EntityStore, LandedDie> landedType() {
        return require(landedType, "LandedDie");
    }

    @Nonnull
    static ComponentType<EntityStore, NumberTile> tileType() {
        return require(tileType, "NumberTile");
    }

    @Nonnull
    static ComponentType<EntityStore, DieGlow> glowType() {
        return require(glowType, "DieGlow");
    }

    private static <T> T require(@Nullable T type, String name) {
        if (type == null) {
            throw new IllegalStateException(name + " component type has not been registered yet.");
        }
        return type;
    }
}
