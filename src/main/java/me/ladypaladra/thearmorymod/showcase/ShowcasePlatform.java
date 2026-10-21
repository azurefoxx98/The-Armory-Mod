package me.ladypaladra.thearmorymod.showcase;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.entities.BlockEntity;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollision;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollisionConfig;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import me.ladypaladra.thearmorymod.TheArmoryMod;
import org.joml.Vector3d;

/**
 * The showcase's rising platform. During the Ceremony the deck at the theatre's centre rises on its pillar, turning once,
 * with everything that stands on it, and after the fireworks it comes back down the same way into its own socket.
 *
 * <p>Hytale carries a player standing on a moving entity whose hard hitbox allows anchoring, and turns the player's facing
 * with it when the collision config asks for it. So the deck travels as entities: one model that draws it, a staircase of
 * invisible boxes that are its floor, a block entity for every piece standing on it, and the stands' figures, frozen so
 * their own gravity stays out of the way. The real blocks leave a few ticks after the moving pieces appear and come back a
 * few ticks before they go, so no frame shows a hole. Every number comes from the trigger effect that starts it.</p>
 */
public final class ShowcasePlatform {

    public static final String EFFECT_TYPE = "ArmoryShowcasePlatform";

    // Every block this writes is the world's own, put back exactly: nothing drops, sounds, sparkles or reshapes its
    // neighbours, and the fillers of a wide block are written as captured instead of being rebuilt.
    private static final int WRITE = SetBlockSettings.NO_SEND_PARTICLES | SetBlockSettings.NO_SET_FILLER
            | SetBlockSettings.NO_BREAK_FILLER | SetBlockSettings.NO_UPDATE_NEIGHBOR_CONNECTIONS
            | SetBlockSettings.NO_SEND_AUDIO | SetBlockSettings.NO_DROP_ITEMS | SetBlockSettings.NO_FIRE_ON_BREAK;

    private static final Map<World, Ride> RIDES = Collections.synchronizedMap(new WeakHashMap<>());

    private ShowcasePlatform() {
    }

    static void register(@Nonnull ComponentRegistryProxy<EntityStore> entities) {
        Deck.setComponentType(entities.registerComponent(Deck.class, "ArmoryShowcasePlatformDeck", Deck.CODEC));
        entities.registerSystem(new Tick());
        TriggerEffect.CODEC.register(EFFECT_TYPE, Effect.class, Effect.CODEC);
    }

    /** Rest to rest with no jolt: speed and acceleration are both zero at the two ends. */
    static double smootherstep(double s) {
        s = Math.max(0.0, Math.min(1.0, s));
        return s * s * s * (s * (s * 6.0 - 15.0) + 10.0);
    }

    /**
     * Starts the rise or the descent. The Ceremony's volume carries one of each, with the numbers the showcase's tools
     * wrote, so the time and the shape stay in one home.
     */
    public static final class Effect extends TriggerEffect {

        @Nonnull
        public static final BuilderCodec<Effect> CODEC = BuilderCodec.builder(Effect.class, Effect::new, BASE_CODEC)
                .append(new KeyedCodec<>("Phase", Codec.STRING, false), (e, v) -> e.phase = v, e -> e.phase).add()
                .append(new KeyedCodec<>("PivotX", Codec.DOUBLE, false), (e, v) -> e.pivotX = v, e -> e.pivotX).add()
                .append(new KeyedCodec<>("PivotY", Codec.DOUBLE, false), (e, v) -> e.pivotY = v, e -> e.pivotY).add()
                .append(new KeyedCodec<>("PivotZ", Codec.DOUBLE, false), (e, v) -> e.pivotZ = v, e -> e.pivotZ).add()
                .append(new KeyedCodec<>("Height", Codec.DOUBLE, false), (e, v) -> e.height = v, e -> e.height).add()
                .append(new KeyedCodec<>("DurationSeconds", Codec.DOUBLE, false), (e, v) -> e.duration = v, e -> e.duration).add()
                .append(new KeyedCodec<>("Turns", Codec.DOUBLE, false), (e, v) -> e.turns = v, e -> e.turns).add()
                .append(new KeyedCodec<>("LayerY", Codec.INT_ARRAY, false), (e, v) -> e.layerY = v, e -> e.layerY).add()
                .append(new KeyedCodec<>("LayerRadius", Codec.DOUBLE_ARRAY, false), (e, v) -> e.layerRadius = v, e -> e.layerRadius).add()
                .append(new KeyedCodec<>("RiderTop", Codec.INTEGER, false), (e, v) -> e.riderTop = v, e -> e.riderTop).add()
                .append(new KeyedCodec<>("DeckModel", Codec.STRING, false), (e, v) -> e.deckModel = v, e -> e.deckModel).add()
                .append(new KeyedCodec<>("FloorModels", Codec.STRING_ARRAY, false), (e, v) -> e.floorModels = v, e -> e.floorModels).add()
                .append(new KeyedCodec<>("Collision", Codec.STRING, false), (e, v) -> e.collision = v, e -> e.collision).add()
                .append(new KeyedCodec<>("RidingState", Codec.STRING, false), (e, v) -> e.ridingState = v, e -> e.ridingState).add()
                .append(new KeyedCodec<>("SettleTicks", Codec.INTEGER, false), (e, v) -> e.settleTicks = v, e -> e.settleTicks).add()
                .build();

        private String phase = "Rise";
        private double pivotX;
        private double pivotY;
        private double pivotZ;
        private double height;
        private double duration = 1.0;
        private double turns;
        private int[] layerY = new int[0];
        private double[] layerRadius = new double[0];
        private int riderTop;
        private String deckModel = "";
        private String[] floorModels = new String[0];
        private String collision = "";
        private String ridingState = "";
        private int settleTicks = 3;

        @Override
        public void execute(@Nonnull TriggerContext context) {
            World world = context.getStore().getExternalData().getWorld();

            if (world == null) {
                return;
            }

            Effect spec = this;
            world.execute(() -> {
                if ("Descend".equals(spec.phase)) {
                    descend(world);
                } else {
                    rise(world, spec);
                }
            });
        }
    }

    private enum Phase { RISING, TOP, DESCENDING, SETTLING }

    // One block captured before the deck leaves, written back exactly.
    private record Cell(int x, int y, int z, int id, int rotation, int filler) {
    }

    // Anything carried: its offset from the pivot at rest and its own yaw at rest.
    private record Rider(Ref<EntityStore> ref, double x, double y, double z, float yaw, boolean figure) {
    }

    private static final class Ride {
        final Effect spec;
        final List<Cell> cells = new ArrayList<>();
        final List<int[]> stands = new ArrayList<>();
        final List<Rider> riders = new ArrayList<>();
        Ref<EntityStore> deck;
        Phase phase = Phase.RISING;
        double t;
        int ticks;
        boolean blocksOut;
        boolean descendAsked;

        Ride(Effect spec) {
            this.spec = spec;
        }
    }

    private static void rise(@Nonnull World world, @Nonnull Effect spec) {
        if (RIDES.containsKey(world)) {
            return;
        }

        Ride ride = new Ride(spec);
        Store<EntityStore> store = world.getEntityStore().getStore();
        ChunkStore chunkStore = world.getChunkStore();
        int top = spec.layerY.length > 0 ? spec.layerY[0] : (int) spec.pivotY - 1;
        double topRadius = spec.layerRadius.length > 0 ? spec.layerRadius[0] : 0.0;
        int span = (int) Math.ceil(topRadius) + 1;

        // The deck's courses, top down, each a disc measured from the cells' corners as the generator lays it.
        for (int i = 0; i < spec.layerY.length && i < spec.layerRadius.length; i++) {
            for (int x = -span; x <= span; x++) {
                for (int z = -span; z <= span; z++) {
                    if (Math.hypot(x, z) < spec.layerRadius[i]) {
                        capture(chunkStore, ride, x, spec.layerY[i], z);
                    }
                }
            }
        }

        // What stands on the deck. A stand stays in its cell and only changes state, so its figure is carried, never
        // rebuilt. Everything else leaves with the deck, drawn meanwhile by a block entity from its origin cell.
        for (int y = top + 1; y <= spec.riderTop; y++) {
            for (int x = -span; x <= span; x++) {
                for (int z = -span; z <= span; z++) {
                    if (Math.hypot(x, z) >= topRadius) {
                        continue;
                    }

                    Ref<ChunkStore> section = section(chunkStore, x, y, z);
                    BlockSection blocks = section != null ? chunkStore.getStore().getComponent(section, BlockSection.getComponentType()) : null;

                    if (blocks == null || blocks.get(x, y, z) == BlockType.EMPTY_ID) {
                        continue;
                    }

                    ShowcaseFigureStand stand = standAt(chunkStore, section, x, y, z);

                    if (stand != null) {
                        ride.stands.add(new int[]{x, y, z});

                        if (ShowcaseFigure.isAlive(stand.figure)) {
                            carryFigure(store, ride, stand.figure);
                        }
                        continue;
                    }

                    Cell cell = capture(chunkStore, ride, x, y, z);

                    if (cell != null && cell.filler() == FillerBlockUtil.NO_FILLER) {
                        spawnPiece(store, ride, cell);
                    }
                }
            }
        }

        ride.deck = spawnModel(store, ride, spec.deckModel, null);

        if (ride.deck == null) {
            TheArmoryMod.LOGGER.atWarning().log("Showcase platform model %s is missing, the platform stays in place", spec.deckModel);
            for (Rider rider : ride.riders) {
                release(store, rider);
            }
            return;
        }

        store.addComponent(ride.deck, Deck.getComponentType(), new Deck(ride));
        HitboxCollisionConfig floor = HitboxCollisionConfig.getAssetMap().getAsset(spec.collision);

        for (String model : spec.floorModels) {
            spawnModel(store, ride, model, floor);
        }

        RIDES.put(world, ride);
    }

    private static void descend(@Nonnull World world) {
        Ride ride = RIDES.get(world);

        if (ride == null) {
            return;
        }

        if (ride.phase == Phase.TOP) {
            ride.phase = Phase.DESCENDING;
            ride.t = 0.0;
        } else {
            ride.descendAsked = true;
        }
    }

    @Nullable
    private static Ref<ChunkStore> section(@Nonnull ChunkStore chunkStore, int x, int y, int z) {
        Ref<ChunkStore> section = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
        return section != null && section.isValid() ? section : null;
    }

    @Nullable
    private static Cell capture(@Nonnull ChunkStore chunkStore, @Nonnull Ride ride, int x, int y, int z) {
        Ref<ChunkStore> section = section(chunkStore, x, y, z);
        BlockSection blocks = section != null ? chunkStore.getStore().getComponent(section, BlockSection.getComponentType()) : null;

        if (blocks == null || blocks.get(x, y, z) == BlockType.EMPTY_ID) {
            return null;
        }

        Cell cell = new Cell(x, y, z, blocks.get(x, y, z), blocks.getRotationIndex(x, y, z), blocks.getFiller(x, y, z));
        ride.cells.add(cell);
        return cell;
    }

    @Nullable
    private static ShowcaseFigureStand standAt(@Nonnull ChunkStore chunkStore, @Nonnull Ref<ChunkStore> section, int x, int y, int z) {
        BlockComponentSection components = chunkStore.getStore().getComponent(section, BlockComponentSection.getComponentType());
        Ref<ChunkStore> block = components != null ? components.getBlockReference(ChunkUtil.indexBlock(x, y, z)) : null;
        return block != null && block.isValid() ? chunkStore.getStore().getComponent(block, ShowcaseFigureStand.getComponentType()) : null;
    }

    private static void carryFigure(@Nonnull Store<EntityStore> store, @Nonnull Ride ride, @Nonnull Ref<EntityStore> figure) {
        TransformComponent transform = store.getComponent(figure, TransformComponent.getComponentType());

        if (transform == null) {
            return;
        }

        Vector3d at = transform.getPosition();
        store.ensureComponent(figure, Frozen.getComponentType());
        ride.riders.add(new Rider(figure, at.x - ride.spec.pivotX, at.y - ride.spec.pivotY, at.z - ride.spec.pivotZ,
                transform.getRotation().yaw(), true));
    }

    private static void spawnPiece(@Nonnull Store<EntityStore> store, @Nonnull Ride ride, @Nonnull Cell cell) {
        BlockType type = BlockType.getAssetMap().getAsset(cell.id());

        if (type == null) {
            return;
        }

        RotationTuple turn = RotationTuple.get(cell.rotation());
        float pitch = turn != null && turn.pitch() != null ? (float) turn.pitch().getRadians() : 0.0F;
        float yaw = turn != null && turn.yaw() != null ? (float) turn.yaw().getRadians() : 0.0F;
        float roll = turn != null && turn.roll() != null ? (float) turn.roll().getRadians() : 0.0F;
        // A block entity is anchored at its block's centre.
        Vector3d at = new Vector3d(cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5);
        Holder<EntityStore> holder = store.getRegistry().newHolder();
        holder.addComponent(BlockEntity.getComponentType(), new BlockEntity(type.getId()));
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(at, new Rotation3f(pitch, yaw, roll)));
        holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());
        Ref<EntityStore> ref = store.addEntity(holder, AddReason.SPAWN);

        if (ref != null && ref.isValid()) {
            ride.riders.add(new Rider(ref, at.x - ride.spec.pivotX, at.y - ride.spec.pivotY, at.z - ride.spec.pivotZ, yaw, false));
        }
    }

    @Nullable
    private static Ref<EntityStore> spawnModel(@Nonnull Store<EntityStore> store, @Nonnull Ride ride, @Nonnull String modelId,
                                               @Nullable HitboxCollisionConfig floor) {
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(modelId);

        if (asset == null) {
            TheArmoryMod.LOGGER.atWarning().log("Showcase platform model %s is not loaded", modelId);
            return null;
        }

        Vector3d at = new Vector3d(ride.spec.pivotX, ride.spec.pivotY, ride.spec.pivotZ);
        Holder<EntityStore> holder = store.getRegistry().newHolder();
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(at, new Rotation3f()));
        holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(Model.createStaticScaledModel(asset, 1.0F)));
        holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());

        if (floor != null) {
            holder.addComponent(HitboxCollision.getComponentType(), new HitboxCollision(floor));
        }

        Ref<EntityStore> ref = store.addEntity(holder, AddReason.SPAWN);

        if (ref == null || !ref.isValid()) {
            return null;
        }

        ride.riders.add(new Rider(ref, 0.0, 0.0, 0.0, 0.0F, false));
        return ref;
    }

    private static void release(@Nonnull Store<EntityStore> store, @Nonnull Rider rider) {
        if (!rider.ref().isValid()) {
            return;
        }

        if (rider.figure()) {
            store.tryRemoveComponent(rider.ref(), Frozen.getComponentType());
        } else {
            store.removeEntity(rider.ref(), RemoveReason.REMOVE);
        }
    }

    // The stands change state first and the deck leaves after, so a stand never stands on nothing in its own state.
    private static void takeBlocks(@Nonnull World world, @Nonnull Ride ride) {
        ChunkStore chunkStore = world.getChunkStore();
        setStands(chunkStore, ride, ride.spec.ridingState);

        for (int i = ride.cells.size() - 1; i >= 0; i--) {
            Cell cell = ride.cells.get(i);
            write(chunkStore, cell.x(), cell.y(), cell.z(), BlockType.EMPTY_ID, BlockType.EMPTY, RotationTuple.NONE_INDEX, FillerBlockUtil.NO_FILLER);
        }
    }

    // The deck returns first and the stands after, so each stand finds its ground before it needs it again.
    private static void returnBlocks(@Nonnull World world, @Nonnull Ride ride) {
        ChunkStore chunkStore = world.getChunkStore();

        for (Cell cell : ride.cells) {
            BlockType type = BlockType.getAssetMap().getAsset(cell.id());

            if (type != null) {
                write(chunkStore, cell.x(), cell.y(), cell.z(), cell.id(), type, cell.rotation(), cell.filler());
            }
        }

        setStands(chunkStore, ride, StateData.NULL_STATE_ID);
    }

    private static void write(@Nonnull ChunkStore chunkStore, int x, int y, int z, int id, @Nonnull BlockType type, int rotation, int filler) {
        Ref<ChunkStore> section = section(chunkStore, x, y, z);

        if (section != null) {
            BlockOperations.setBlock(chunkStore, section, x, y, z, id, type, rotation, filler, WRITE);
        }
    }

    private static void setStands(@Nonnull ChunkStore chunkStore, @Nonnull Ride ride, @Nonnull String state) {
        for (int[] at : ride.stands) {
            Ref<ChunkStore> section = section(chunkStore, at[0], at[1], at[2]);
            BlockSection blocks = section != null ? chunkStore.getStore().getComponent(section, BlockSection.getComponentType()) : null;
            BlockType type = blocks != null ? BlockType.getAssetMap().getAsset(blocks.get(at[0], at[1], at[2])) : null;

            if (type != null) {
                BlockOperations.setBlockInteractionState(chunkStore, section, at[0], at[1], at[2], type, state, false);
            }
        }
    }

    // A visitor who jumped into the socket while the deck was up is put back on top before the stone returns.
    private static void liftFromSocket(@Nonnull World world, @Nonnull Ride ride) {
        Store<EntityStore> store = world.getEntityStore().getStore();
        double radius = ride.spec.layerRadius.length > 0 ? ride.spec.layerRadius[0] + 0.5 : 0.0;
        int bottom = ride.spec.layerY.length > 0 ? ride.spec.layerY[ride.spec.layerY.length - 1] : (int) ride.spec.pivotY;

        for (PlayerRef player : world.getPlayerRefs()) {
            Ref<EntityStore> ref = player.getReference();
            TransformComponent transform = ref != null && ref.isValid() ? store.getComponent(ref, TransformComponent.getComponentType()) : null;

            if (transform == null) {
                continue;
            }

            Vector3d at = transform.getPosition();

            if (Math.hypot(at.x - ride.spec.pivotX, at.z - ride.spec.pivotZ) < radius && at.y >= bottom && at.y < ride.spec.pivotY) {
                HeadRotation head = store.getComponent(ref, HeadRotation.getComponentType());
                Rotation3f look = head != null ? new Rotation3f(head.getRotation()) : new Rotation3f(transform.getRotation());
                store.addComponent(ref, Teleport.getComponentType(),
                        Teleport.createForPlayer(new Vector3d(at.x, ride.spec.pivotY, at.z), look));
            }
        }
    }

    // Every carried thing at one pose: its rest offset turned round the pivot, raised, and its own yaw turned with it.
    private static void place(@Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Ride ride, double up, double turn) {
        Effect spec = ride.spec;

        for (Rider rider : ride.riders) {
            if (!rider.ref().isValid()) {
                continue;
            }

            TransformComponent transform = commandBuffer.getComponent(rider.ref(), TransformComponent.getComponentType());

            if (transform == null) {
                continue;
            }

            Vector3d offset = new Vector3d(rider.x(), 0.0, rider.z()).rotateY(turn);
            transform.getPosition().set(spec.pivotX + offset.x, spec.pivotY + rider.y() + up, spec.pivotZ + offset.z);
            float yaw = (float) (rider.yaw() + turn);
            transform.getRotation().setYaw(yaw);

            HeadRotation head = commandBuffer.getComponent(rider.ref(), HeadRotation.getComponentType());

            if (head != null) {
                head.getRotation().setYaw(yaw);
            }
        }
    }

    /** Marks the deck's entity, which carries the ride, so the ride is stepped once per server tick. Never saved. */
    public static final class Deck implements Component<EntityStore> {

        @Nonnull
        public static final BuilderCodec<Deck> CODEC = BuilderCodec.builder(Deck.class, Deck::new).build();

        private static ComponentType<EntityStore, Deck> componentType;

        @Nullable
        private transient Ride ride;

        public Deck() {
        }

        Deck(@Nonnull Ride ride) {
            this.ride = ride;
        }

        public static void setComponentType(@Nonnull ComponentType<EntityStore, Deck> type) {
            componentType = type;
        }

        public static ComponentType<EntityStore, Deck> getComponentType() {
            return componentType;
        }

        @Nonnull
        @Override
        public Component<EntityStore> clone() {
            return ride != null ? new Deck(ride) : new Deck();
        }
    }

    /**
     * Steps the ride: waits a few ticks, takes the real blocks, rises, holds at the top until the descent is asked, comes
     * down the same curve backwards, gives the blocks back, and after a few more ticks lets everything go.
     */
    public static class Tick extends EntityTickingSystem<EntityStore> {

        @Override
        public Query<EntityStore> getQuery() {
            return Deck.getComponentType();
        }

        @Override
        public void tick(
                float dt,
                int index,
                @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer
        ) {
            Deck deck = archetypeChunk.getComponent(index, Deck.getComponentType());
            Ride ride = deck != null ? deck.ride : null;
            World world = store.getExternalData().getWorld();

            if (ride == null || world == null) {
                return;
            }

            Effect spec = ride.spec;
            double duration = Math.max(spec.duration, 0.05);
            ride.ticks++;

            switch (ride.phase) {
                case RISING -> {
                    if (!ride.blocksOut) {
                        if (ride.ticks >= spec.settleTicks) {
                            ride.blocksOut = true;
                            world.execute(() -> takeBlocks(world, ride));
                        }
                        place(commandBuffer, ride, 0.0, 0.0);
                        return;
                    }

                    ride.t = Math.min(duration, ride.t + dt);
                    double s = smootherstep(ride.t / duration);
                    place(commandBuffer, ride, spec.height * s, 2.0 * Math.PI * spec.turns * s);

                    if (ride.t >= duration) {
                        ride.phase = ride.descendAsked ? Phase.DESCENDING : Phase.TOP;
                        ride.t = 0.0;
                    }
                }
                case TOP -> place(commandBuffer, ride, spec.height, 2.0 * Math.PI * spec.turns);
                case DESCENDING -> {
                    ride.t = Math.min(duration, ride.t + dt);
                    double s = smootherstep((duration - ride.t) / duration);
                    place(commandBuffer, ride, spec.height * s, 2.0 * Math.PI * spec.turns * s);

                    if (ride.t >= duration) {
                        place(commandBuffer, ride, 0.0, 0.0);
                        ride.phase = Phase.SETTLING;
                        ride.ticks = 0;
                        world.execute(() -> {
                            liftFromSocket(world, ride);
                            returnBlocks(world, ride);
                        });
                    }
                }
                case SETTLING -> {
                    place(commandBuffer, ride, 0.0, 0.0);

                    if (ride.ticks >= spec.settleTicks) {
                        deck.ride = null;
                        world.execute(() -> {
                            Store<EntityStore> entities = world.getEntityStore().getStore();
                            for (Rider rider : ride.riders) {
                                release(entities, rider);
                            }
                            RIDES.remove(world);
                        });
                    }
                }
            }
        }
    }
}
