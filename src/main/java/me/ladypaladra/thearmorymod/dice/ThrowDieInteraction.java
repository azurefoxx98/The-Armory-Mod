package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.validation.Validators;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.projectile.ProjectileModule;
import com.hypixel.hytale.server.core.modules.projectile.config.ProjectileConfig;
import com.hypixel.hytale.server.core.modules.projectile.config.StandardPhysicsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Throws the die in the hand. One of these ends each step of the item's charge, carrying how long that step was held,
 * so the longer the hold, the harder the throw.
 *
 * <p>The die is spawned by the server alone, with no client prediction, so every player, the thrower included, sees
 * the same die. Crouching throws a handful: up to five of the dice in the stack at once, gently.</p>
 */
public final class ThrowDieInteraction extends SimpleInstantInteraction {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * The most dice a crouched throw sends from one stack.
     */
    static final int HANDFUL = 5;

    /**
     * Blocks between dice leaving the hand together, across the throw, so a pair or a handful does not start as one.
     */
    static final double SPREAD = 0.18;

    @Nonnull
    public static final BuilderCodec<ThrowDieInteraction> CODEC = BuilderCodec
            .builder(ThrowDieInteraction.class, ThrowDieInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Throws the die held in the hand, as hard as the hold this step stands for.")
            .append(
                    new KeyedCodec<>("Hold", Codec.DOUBLE),
                    (interaction, value) -> interaction.hold = value,
                    interaction -> interaction.hold
            )
            .addValidator(Validators.greaterThanOrEqual(0.0))
            .documentation("Seconds of holding this step stands for. The throw's strength follows from it.")
            .add()
            .build();

    private double hold;

    public ThrowDieInteraction() {
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
        ItemStack held = context.getHeldItem();
        ItemContainer container = context.getHeldItemContainer();
        DieType dieType = held != null ? DieType.byItemId(held.getItemId()) : null;
        DiceCatalog catalog = DiceModule.catalog();
        if (commandBuffer == null || dieType == null || container == null || catalog == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        Ref<EntityStore> thrower = context.getEntity();
        PlayerRef playerRef = commandBuffer.getComponent(thrower, PlayerRef.getComponentType());
        if (playerRef == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        MovementStatesComponent movement = commandBuffer.getComponent(thrower, MovementStatesComponent.getComponentType());
        MovementStates states = movement != null ? movement.getMovementStates() : null;
        boolean crouching = states != null && states.crouching;
        boolean sprinting = states != null && states.sprinting;
        boolean airborne = states != null && (states.jumping || states.falling);
        Velocity velocity = commandBuffer.getComponent(thrower, Velocity.getComponentType());
        Vector3d carried = velocity != null ? new Vector3d(velocity.getClientVelocity()) : new Vector3d();

        int count = crouching ? Math.min(HANDFUL, held.getQuantity()) : 1;
        var taken = container.removeItemStackFromSlot(context.getHeldItemSlot(), held, count);
        if (!taken.succeeded()) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        // The hand changed: tell the chain, or the engine cancels the rest of it as a swapped item.
        context.setHeldItem(taken.getSlotAfter());

        Transform look = TargetUtil.getLook(thrower, commandBuffer);
        Vector3d eye = new Vector3d(look.getPosition());
        Vector3d aim = new Vector3d(look.getDirection()).normalize();
        Vector3d across = new Vector3d(aim.z, 0.0, -aim.x);
        if (across.lengthSquared() < 1.0E-8) {
            across.set(1.0, 0.0, 0.0);
        }
        across.normalize();

        RandomGenerator rng = ThreadLocalRandom.current();
        double power = ThrowFeel.power(hold);
        List<DieShape> shapes = catalog.shapesOf(dieType);
        ThrowGroup group = new ThrowGroup(dieType, playerRef.getUuid(), playerRef.getUsername(), count);
        int dice = count * shapes.size();
        int slot = 0;
        for (int member = 0; member < count; member++) {
            DiceModule.ledger().flying(group.ledgerId(member), group.owner(), dieType.itemId());
            for (int part = 0; part < shapes.size(); part++) {
                DieShape shape = shapes.get(part);
                double offset = (slot - (dice - 1) / 2.0) * SPREAD;
                slot++;
                Vector3d start = new Vector3d(eye).fma(offset, across);
                Vector3d launch = ThrowFeel.launchVelocity(power, aim, carried, crouching, sprinting, airborne, rng);
                launch(commandBuffer, thrower, dieType, shape, group, member, part, start, launch, eye, rng);
            }
        }
    }

    private static void launch(@Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Ref<EntityStore> thrower,
                               @Nonnull DieType type, @Nonnull DieShape shape, @Nonnull ThrowGroup group, int member,
                               int part, @Nonnull Vector3d start, @Nonnull Vector3d launch, @Nonnull Vector3d eye,
                               @Nonnull RandomGenerator rng) {
        String configId = "Armory_Dice_Throw_" + shape.key();
        ProjectileConfig config = ProjectileConfig.getAssetMap().getAsset(configId);
        if (config == null || config.getLaunchForce() <= 0) {
            LOGGER.atWarning().log("Die %s not thrown: projectile config %s is missing", shape.key(), configId);
            DiceModule.ledger().lost(group.ledgerId(member));
            group.lose(member);
            return;
        }
        double speed = launch.length();
        Vector3d direction = speed > 1.0E-6 ? new Vector3d(launch).div(speed) : new Vector3d(0, -1, 0);
        float multiplier = (float) (speed / config.getLaunchForce());

        DieFlight flight = new DieFlight(type, shape, DiceModule.MOTION, group, member, part);
        DiceModule.MOTION.launch(flight.spin, launch, rng);
        flight.origin.set(eye);
        Vector3d velocity = new Vector3d(launch);

        Ref<EntityStore> ref = ProjectileModule.get().spawnProjectile(thrower, commandBuffer, config, start, direction,
                1.0f, multiplier);
        // The projectile exists once the buffer runs: its flight and its handlers go on it then.
        commandBuffer.run(store -> attach(store, ref, flight, velocity));
    }

    private static void attach(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                               @Nonnull DieFlight flight, @Nonnull Vector3d velocity) {
        if (!ref.isValid()) {
            DieFlightSystems.lose(flight, store);
            return;
        }
        StandardPhysicsProvider physics = store.getComponent(ref, StandardPhysicsProvider.getComponentType());
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        DiceCatalog catalog = DiceModule.catalog();
        if (DiceModule.TRUE_PHYSICS && physics != null && transform != null && catalog != null) {
            // The engine lets go of the die: an inactive projectile is never moved or collided by it, only has its
            // velocity held at zero, and the die's own rigid body flies it from where the engine spawned it.
            physics.setState(StandardPhysicsProvider.STATE.INACTIVE);
            physics.getVelocity().zero();
            flight.rigid = new RigidFlight(flight.shape, catalog.hull(flight.shape.key()), flight.spin,
                    transform.getPosition(), velocity);
            flight.group.bodies.add(flight.rigid);
        }
        if (physics != null) {
            // These replace the engine's own handlers, which would delete the die if its thrower logged off.
            physics.setBounceConsumer((self, position, commandBuffer) ->
                    DieFlightSystems.onBounce(self, position, physics, commandBuffer));
            physics.setImpactConsumer((self, position, block, target, detail, commandBuffer) ->
                    DieFlightSystems.onImpact(self, position, target, physics, commandBuffer));
        }
        store.putComponent(ref, DiceModule.flightType(), flight);
    }
}
