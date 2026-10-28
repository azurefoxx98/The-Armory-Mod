package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.collision.EntityRefCollisionProvider;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.entity.component.PropComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.system.TransformSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.modules.projectile.config.StandardPhysicsProvider;
import com.hypixel.hytale.server.core.modules.projectile.system.StandardPhysicsTickSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * A die from the moment it leaves the hand to the moment it lies on the ground as a landed die.
 */
public final class DieFlightSystems {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Blocks per second under which a die counts as still, and how many ticks it must stay so before it settles. The
     * engine's own rest catches a die stopping on the ground. This one also catches a die that sank in water or wedged
     * itself somewhere the engine never calls a rest.
     */
    static final double STILL_SPEED = 0.08;
    static final int STILL_TICKS = 8;

    /**
     * The longest a die may fly, in ticks, before it settles where it is.
     */
    static final int MAX_FLIGHT_TICKS = 30 * 20;

    /**
     * Ticks a die takes to topple onto its face once it stops.
     */
    static final int TOPPLE_TICKS = 7;

    /**
     * Ticks a die flown by its own physics takes to settle onto its resting model's exact turn: it already lies flat,
     * within a few degrees, so this is a snap no one sees.
     */
    static final int SNAP_TICKS = 3;

    /**
     * A strike harder than this, in blocks per second, clacks, and two clacks of one die are at least this many ticks
     * apart, so a die rattling across a corner does not clack every tick.
     */
    static final double CLACK_SPEED = 1.2;
    static final int CLACK_GAP = 3;

    /**
     * Below this height a die has fallen out of the world: the world's floor, under which no chunk exists to hold an
     * entity. The engine deletes anything that comes to rest down there, so a die must never be laid down below it
     * (measured on 0.7.4: a die thrown down a shaft to the bottom stopped under the floor, was laid down, and the engine
     * removed it as being in a chunk that cannot be loaded).
     */
    static final double OUT_OF_WORLD_Y = ChunkUtil.MIN_Y;

    /**
     * A die bouncing off a creature keeps this much of its speed, and leaves it a little upward.
     */
    static final double CREATURE_BOUNCE = 0.35;
    static final double CREATURE_LIFT = 1.2;

    /**
     * Ticks a creature a die just bounced off is left out of its path, and how far past this tick's path creatures
     * are looked for.
     */
    static final int CREATURE_GRACE = 10;
    static final double CREATURE_SEARCH = 8.0;

    /**
     * The least sideways speed, blocks per second, a die is knocked off a creature with.
     */
    static final double CREATURE_KNOCK = 1.8;

    static final String PICK_UP_INTERACTION = "Armory_Dice_PickUp";
    static final String PICK_UP_HINT = "server.armoryDice.pickUpHint";

    private DieFlightSystems() {
    }

    /**
     * Turns every flying die each tick, after the engine has moved it and before its transform is sent.
     */
    public static final class Turn extends EntityTickingSystem<EntityStore> {

        private final Set<Dependency<EntityStore>> dependencies = Set.of(
                new SystemDependency<>(Order.AFTER, StandardPhysicsTickSystem.class),
                new SystemDependency<>(Order.BEFORE, TransformSystems.EntityTrackerUpdate.class)
        );

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Query.and(DiceModule.flightType(), StandardPhysicsProvider.getComponentType(),
                    TransformComponent.getComponentType());
        }

        @Nonnull
        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return dependencies;
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                         @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            DieFlight flight = chunk.getComponent(index, DiceModule.flightType());
            StandardPhysicsProvider physics = chunk.getComponent(index, StandardPhysicsProvider.getComponentType());
            TransformComponent transform = chunk.getComponent(index, TransformComponent.getComponentType());
            if (flight == null || physics == null || transform == null || flight.phase == DieFlight.Phase.DONE) {
                return;
            }
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            double step = 1.0 / 30.0;
            flight.age++;

            if (flight.phase == DieFlight.Phase.FLYING && flight.rigid != null) {
                flyRigid(ref, flight, physics, transform, store, commandBuffer);
                return;
            }
            if (flight.phase == DieFlight.Phase.FLYING) {
                // Caught a tick before it crosses the floor: once under it, the engine warns that the die moved into a
                // chunk that cannot be loaded before this system sees it.
                double next = transform.getPosition().y + physics.getVelocity().y * step * 1.5;
                if (transform.getPosition().y < OUT_OF_WORLD_Y || next < OUT_OF_WORLD_Y) {
                    commandBuffer.tryRemoveEntity(ref, RemoveReason.REMOVE);
                    return;
                }
                flight.motion.tick(flight.spin, step);
                write(flight.spin.orientation, transform);

                double speed = physics.getVelocity().length();
                flight.stillTicks = speed < STILL_SPEED ? flight.stillTicks + 1 : 0;
                boolean rested = physics.getState() == StandardPhysicsProvider.STATE.RESTING;
                if (rested || flight.stillTicks >= STILL_TICKS || flight.age >= MAX_FLIGHT_TICKS) {
                    beginSettle(flight, physics, transform);
                    if (!flight.motion.topples()) {
                        handOver(ref, flight, transform, commandBuffer);
                    }
                }
                return;
            }

            // Toppling: ease from where the die stopped onto its face, lowering its middle to its resting height.
            flight.toppleTick++;
            double t = Math.min(1.0, (double) flight.toppleTick / flight.toppleTicks);
            double ease = t * t * (3.0 - 2.0 * t);
            Quaterniond now = new Quaterniond(flight.toppleFrom).slerp(flight.toppleTo, ease);
            write(now, transform);
            double restY = flight.floorY + flight.face.restMiddle();
            transform.getPosition().y = flight.toppleStart.y + (restY - flight.toppleStart.y) * ease;
            if (t >= 1.0) {
                handOver(ref, flight, transform, commandBuffer);
            }
        }
    }

    /**
     * A flying die removed by anything but its own landing was lost to the world: out of the world, its chunk
     * unloaded, the server stopping. Its owner gets it back.
     */
    public static final class Lost extends RefSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return DiceModule.flightType();
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                                  @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                                   @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            DieFlight flight = store.getComponent(ref, DiceModule.flightType());
            if (flight != null && !flight.handedOver) {
                flight.phase = DieFlight.Phase.DONE;
                lose(flight, commandBuffer);
            }
        }
    }

    /**
     * The engine bounced a die: the bounce changes its spin, clacks and sparks.
     */
    static void onBounce(@Nonnull Ref<EntityStore> ref, @Nonnull Vector3d position,
                         @Nonnull StandardPhysicsProvider physics, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        DieFlight flight = commandBuffer.getComponent(ref, DiceModule.flightType());
        if (flight == null || flight.phase != DieFlight.Phase.FLYING) {
            return;
        }
        Vector3d before = new Vector3d(physics.getStateBefore().velocity);
        Vector3d after = new Vector3d(physics.getVelocity());
        Vector3d normal = new Vector3d(physics.getContactNormal());
        if (normal.lengthSquared() < 1.0E-9) {
            normal.set(0.0, 1.0, 0.0);
        } else {
            normal.normalize();
        }
        flight.motion.bounce(flight.spin, before, after, normal, flight.shape.radius(), ThreadLocalRandom.current());
        DiceShow.bounce(flight.shape, position, Math.abs(before.dot(normal)), commandBuffer);
    }

    /**
     * The engine stopped a die on a creature, where it would hang in the air for good. It bounces off instead.
     */
    static void onImpact(@Nonnull Ref<EntityStore> ref, @Nonnull Vector3d position, @Nullable Ref<EntityStore> target,
                         @Nonnull StandardPhysicsProvider physics, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (target == null || !target.isValid()) {
            // A rest on the ground: the turn system sees it and settles the die.
            return;
        }
        TransformComponent other = commandBuffer.getComponent(target, TransformComponent.getComponentType());
        Vector3d away = other != null ? new Vector3d(position).sub(other.getPosition()) : new Vector3d();
        away.y = 0.0;
        if (away.lengthSquared() < 1.0E-6) {
            away.set(physics.getVelocity()).negate();
            away.y = 0.0;
        }
        if (away.lengthSquared() < 1.0E-6) {
            away.set(1.0, 0.0, 0.0);
        }
        away.normalize();
        Vector3d velocity = physics.getVelocity();
        double speed = velocity.length() * CREATURE_BOUNCE;
        velocity.set(away).mul(speed).add(0.0, CREATURE_LIFT, 0.0);
        position.fma(0.05, away);
        physics.setState(StandardPhysicsProvider.STATE.ACTIVE);
        DieFlight flight = commandBuffer.getComponent(ref, DiceModule.flightType());
        if (flight != null) {
            flight.motion.bounce(flight.spin, velocity, velocity, away, flight.shape.radius(), ThreadLocalRandom.current());
        }
    }

    /**
     * A die lost before it could land: its item is owed to its owner, a D100's other die goes with it, and its throw
     * is read without it.
     */
    static void lose(@Nonnull DieFlight flight, @Nonnull ComponentAccessor<EntityStore> accessor) {
        ThrowGroup group = flight.group;
        // A lost die is no longer anywhere for the others to knock against.
        group.bodies.remove(flight.rigid);
        if (group.isLost(flight.member)) {
            return;
        }
        DiceModule.ledger().lost(group.ledgerId(flight.member));
        List<Ref<EntityStore>> orphans = group.lose(flight.member);
        // The orphans are claimed first: their pair is already owed back, so their removal must not owe it again.
        if (accessor instanceof CommandBuffer<EntityStore> commandBuffer) {
            for (Ref<EntityStore> orphan : orphans) {
                if (orphan.isValid() && claim(orphan, commandBuffer)) {
                    commandBuffer.tryRemoveEntity(orphan, RemoveReason.REMOVE);
                }
            }
            if (group.claimFinished()) {
                DiceShow.announce(group, commandBuffer);
            }
        } else if (accessor instanceof Store<EntityStore> store) {
            for (Ref<EntityStore> orphan : orphans) {
                if (orphan.isValid() && claim(orphan, store)) {
                    store.removeEntity(orphan, RemoveReason.REMOVE);
                }
            }
        }
    }

    private static boolean claim(@Nonnull Ref<EntityStore> landed, @Nonnull ComponentAccessor<EntityStore> accessor) {
        LandedDie die = accessor.getComponent(landed, DiceModule.landedType());
        return die != null && die.claim();
    }

    private static void beginSettle(@Nonnull DieFlight flight, @Nonnull StandardPhysicsProvider physics,
                                    @Nonnull TransformComponent transform) {
        // Frozen from here: the engine stops moving it, and the topple and the landing are ours.
        physics.setState(StandardPhysicsProvider.STATE.INACTIVE);
        physics.getVelocity().zero();
        flight.face = flight.motion.result(flight.spin, flight.shape, ThreadLocalRandom.current());
        flight.restYaw = DieOrientation.restYaw(flight.spin.orientation, flight.face);
        flight.toppleFrom.set(flight.spin.orientation);
        DieOrientation.resting(flight.restYaw, flight.face, flight.toppleTo);
        flight.toppleStart.set(transform.getPosition());
        // The flying box is a cube about the die's middle, as wide as the die's farthest corner.
        flight.floorY = transform.getPosition().y - flight.shape.radius();
        flight.phase = DieFlight.Phase.TOPPLING;
        flight.toppleTick = 0;
        flight.toppleTicks = TOPPLE_TICKS;
    }

    /**
     * One tick of a die flown by its own rigid body: the physics moves and turns it, a creature in its way knocks it
     * back, a hard strike clacks, and once it lies flat on a face it is laid down as that face.
     */
    private static void flyRigid(@Nonnull Ref<EntityStore> ref, @Nonnull DieFlight flight,
                                 @Nonnull StandardPhysicsProvider physics, @Nonnull TransformComponent transform,
                                 @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        RigidFlight rigid = flight.rigid;
        World world = store.getExternalData().getWorld();
        EngineDieWorld blocks = EngineDieWorld.on(world);
        RandomGenerator rng = ThreadLocalRandom.current();
        double dt = 1.0 / world.getTps();
        if (flight.age == 1) {
            rigid.unstick(blocks, flight.origin);
        }

        rigid.knockAgainst(flight.group.bodies, rng);
        Vector3d before = new Vector3d(rigid.body.position);
        RigidFlight.Status status = rigid.tick(blocks, dt, rng);
        Vector3d middle = rigid.modelPosition(new Vector3d());
        if (middle.y < OUT_OF_WORLD_Y || middle.y + rigid.body.velocity.y * dt * 1.5 < OUT_OF_WORLD_Y) {
            commandBuffer.tryRemoveEntity(ref, RemoveReason.REMOVE);
            return;
        }
        if (status == RigidFlight.Status.FLYING && creature(ref, flight, rigid, physics, before, store, commandBuffer)) {
            rigid.modelPosition(middle);
        }
        transform.getPosition().set(middle);
        write(rigid.body.spin.orientation, transform);

        flight.sinceClack++;
        double struck = rigid.report.hardest;
        if (struck > CLACK_SPEED && flight.sinceClack >= CLACK_GAP) {
            flight.sinceClack = 0;
            DiceShow.bounce(flight.shape, middle, struck, commandBuffer);
        }

        if (status == RigidFlight.Status.FLYING) {
            return;
        }
        physics.getVelocity().zero();
        if (status == RigidFlight.Status.LANDED) {
            // Already flat on its face: the settle only snaps it onto the resting model's exact turn and height.
            flight.face = rigid.face();
            flight.toppleTicks = SNAP_TICKS;
        } else {
            // Stuck cocked: laid on the face nearest up, as the tumble lays a die down.
            flight.face = DieOrientation.topFace(rigid.body.spin.orientation, flight.shape);
            flight.toppleTicks = TOPPLE_TICKS;
            LOGGER.atInfo().log("A %s stayed cocked after %d nudges, laid on its face nearest up", flight.shape.key(),
                    rigid.nudges);
        }
        flight.restYaw = DieOrientation.restYaw(rigid.body.spin.orientation, flight.face);
        flight.toppleFrom.set(rigid.body.spin.orientation);
        DieOrientation.resting(flight.restYaw, flight.face, flight.toppleTo);
        flight.toppleStart.set(middle);
        flight.floorY = rigid.floorY();
        flight.phase = DieFlight.Phase.TOPPLING;
        flight.toppleTick = 0;
    }

    /**
     * Sweeps the die's box along this tick's path through the creatures and players around it, with the engine's own
     * test and filter, the thrower left out as the engine leaves out a projectile's creator. A die that meets one is
     * knocked back off it, a little up, keeping a third of its speed.
     *
     * @return whether the die met one and was moved
     */
    private static boolean creature(@Nonnull Ref<EntityStore> ref, @Nonnull DieFlight flight, @Nonnull RigidFlight rigid,
                                    @Nonnull StandardPhysicsProvider physics, @Nonnull Vector3d before,
                                    @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Vector3d movement = new Vector3d(rigid.body.position).sub(before);
        if (movement.lengthSquared() < 1.0E-10) {
            return false;
        }
        Vector3d min = new Vector3d();
        Vector3d max = new Vector3d();
        rigid.bounds(min, max);
        Ref<EntityStore> creator = physics.getCreatorUuid() != null
                ? store.getExternalData().getRefFromUUID(physics.getCreatorUuid()) : null;
        EntityRefCollisionProvider provider = physics.getEntityCollisionProvider();
        provider.clear();
        // The creature just bounced off is left out for a moment: a die still overlapping it would otherwise be
        // knocked back off it again every tick and hang there.
        Ref<EntityStore> recent = flight.age - flight.lastHitAge <= CREATURE_GRACE ? flight.lastHit : null;
        double hit = provider.computeNearest(commandBuffer, before, movement, new Box(min, max),
                movement.length() + CREATURE_SEARCH,
                (other, buffer) -> EntityRefCollisionProvider.defaultEntityFilter(other, buffer) && !other.equals(recent),
                ref, creator);
        // At the very start of the path the die is already inside the creature: it passes through and falls, where a
        // knock from inside would juggle it there for good.
        if (hit <= 1.0E-6 || hit > 1.0 || provider.getCount() == 0) {
            provider.clear();
            return false;
        }
        Ref<EntityStore> target = provider.getContact(0).getEntityReference();
        provider.clear();
        TransformComponent other = target != null && target.isValid()
                ? commandBuffer.getComponent(target, TransformComponent.getComponentType()) : null;
        Vector3d at = new Vector3d(before).fma(Math.max(0.0, hit - 0.05), movement);
        Vector3d away = other != null ? new Vector3d(at).sub(other.getPosition()) : new Vector3d(movement).negate();
        away.y = 0.0;
        if (away.lengthSquared() < 1.0E-6) {
            away.set(movement).negate();
            away.y = 0.0;
        }
        if (away.lengthSquared() < 1.0E-6) {
            away.set(1.0, 0.0, 0.0);
        }
        away.normalize();
        // A die already moving away from the creature, one that started inside it or dropped onto it from above, is
        // let go its way.
        if (movement.x * away.x + movement.z * away.z > 0.0) {
            return false;
        }
        flight.lastHit = target;
        flight.lastHitAge = flight.age;
        // Never less than a firm sideways knock, so a die dropped on a head is thrown off it, not bounced in place.
        double speed = Math.max(CREATURE_KNOCK, rigid.body.velocity.length() * CREATURE_BOUNCE);
        rigid.body.position.set(at);
        rigid.body.velocity.set(away).mul(speed).add(0.0, CREATURE_LIFT, 0.0);
        rigid.body.spin.angularVelocity.add(TumbleMotion.randomUnit(ThreadLocalRandom.current(), new Vector3d())
                .mul(TumbleMotion.KNOCK * speed));
        rigid.stillTicks = 0;
        return true;
    }

    /**
     * Swaps the projectile for a landed die: the resting model of the face that ended up, turned by the same yaw, on
     * the same spot. From here the world saves it.
     */
    private static void handOver(@Nonnull Ref<EntityStore> ref, @Nonnull DieFlight flight,
                                 @Nonnull TransformComponent transform, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        flight.phase = DieFlight.Phase.DONE;
        flight.handedOver = true;
        commandBuffer.tryRemoveEntity(ref, RemoveReason.REMOVE);

        ThrowGroup group = flight.group;
        if (group.isLost(flight.member)) {
            // Its D100 partner was lost: the pair has already gone back to its owner, so this die leaves too.
            return;
        }

        ModelAsset asset = ModelAsset.getAssetMap().getAsset(flight.face.restModel());
        if (asset == null) {
            LOGGER.atWarning().log("Resting model %s is missing; the die goes back to its owner", flight.face.restModel());
            flight.handedOver = false;
            lose(flight, commandBuffer);
            return;
        }
        Model model = Model.createRandomScaleModel(asset);
        Vector3d spot = new Vector3d(transform.getPosition().x, flight.floorY, transform.getPosition().z);

        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(TransformComponent.getComponentType(),
                new TransformComponent(spot, new Rotation3f(0.0f, (float) flight.restYaw, 0.0f)));
        holder.ensureComponent(UUIDComponent.getComponentType());
        holder.addComponent(PersistentModel.getComponentType(), new PersistentModel(model.toReference()));
        holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
        holder.addComponent(BoundingBox.getComponentType(), new BoundingBox(model.getBoundingBox()));
        holder.ensureComponent(PropComponent.getComponentType());
        holder.ensureComponent(Interactable.getComponentType());
        Map<InteractionType, String> use = new EnumMap<>(InteractionType.class);
        use.put(InteractionType.Use, PICK_UP_INTERACTION);
        Interactions interactions = new Interactions(use);
        interactions.setInteractionHint(PICK_UP_HINT);
        holder.addComponent(Interactions.getComponentType(), interactions);
        holder.addComponent(NetworkId.getComponentType(),
                new NetworkId(commandBuffer.getExternalData().takeNextNetworkId()));
        holder.addComponent(DiceModule.landedType(), new LandedDie(flight.type.itemId(), flight.shape.key(),
                flight.face.index(), group.owner(), group.pairId(flight.member), spot.y, flight.restYaw));
        holder.addComponent(DiceModule.glowType(), new DieGlow(flight.shape.glow()));
        Ref<EntityStore> landed = commandBuffer.addEntity(holder, AddReason.SPAWN);

        if (group.land(flight.member, flight.part, flight.face, spot, landed)) {
            DiceModule.ledger().landed(group.ledgerId(flight.member));
        }
        DiceShow.awaken(flight.shape, spot, flight.face, commandBuffer);
        if (group.claimFinished()) {
            DiceShow.announce(group, commandBuffer);
        }
    }

    /**
     * Writes a turn as the engine's yaw, pitch and roll, the inverse of how the engine composes them.
     */
    static void write(@Nonnull Quaterniond orientation, @Nonnull TransformComponent transform) {
        Vector3d e = orientation.getEulerAnglesYXZ(new Vector3d());
        transform.getRotation().set((float) e.x, (float) e.y, (float) e.z);
    }
}
