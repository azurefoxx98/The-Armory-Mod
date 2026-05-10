package me.ladypaladra.thearmorymod.engineeringrig.systems;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.protocol.VelocityConfig;
import com.hypixel.hytale.protocol.VelocityThresholdStyle;
import com.hypixel.hytale.protocol.packets.entities.ChangeVelocity;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class EngineeringRigMobilitySystem extends EntityTickingSystem<EntityStore> {

    private static final String[] ENGINEERING_RIG_PATTERNS = {
            "engineeringrig",
            "engineering_rig",
            "armor_engineeringrig",
            "armor_engineering_rig"
    };

    private static final float HORIZONTAL_BOOST_AMOUNT = 0.055F;
    private static final float HORIZONTAL_BOOST_INTERVAL_SECONDS = 0.12F;

    private static final double MIN_HORIZONTAL_SPEED_TO_BOOST = 1.25D;

    private static final double MAX_HORIZONTAL_SPEED_TO_BOOST = 8.0D;

    private static final double MAX_VERTICAL_SPEED_FOR_GROUND_BOOST = 0.65D;

    private static final float JUMP_BOOST_AMOUNT = 0.16F;
    private static final float JUMP_BOOST_COOLDOWN_SECONDS = 0.55F;
    private static final double JUMP_START_VERTICAL_SPEED = 0.65D;
    private static final double PREVIOUS_VERTICAL_SPEED_MAX_FOR_JUMP = 0.15D;

    private static final VelocityConfig HORIZONTAL_VELOCITY_CONFIG = new VelocityConfig(
            8.0F,
            0.35F,
            3.0F,
            0.18F,
            0.01F,
            VelocityThresholdStyle.Linear
    );

    private static final VelocityConfig JUMP_VELOCITY_CONFIG = new VelocityConfig(
            2.0F,
            0.15F,
            1.0F,
            0.10F,
            0.01F,
            VelocityThresholdStyle.Linear
    );

    private final Query<EntityStore> query;

    private final Map<UUID, MobilityState> states = new HashMap<>();

    public EngineeringRigMobilitySystem() {
        this.query = Archetype.of(new ComponentType[]{
                Player.getComponentType(),
                PlayerRef.getComponentType(),
                UUIDComponent.getComponentType(),
                TransformComponent.getComponentType(),
                InventoryComponent.Armor.getComponentType()
        });
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> chunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        if (dt <= 0.0F) {
            return;
        }

        Ref<EntityStore> entityRef = chunk.getReferenceTo(index);
        if (entityRef == null || !entityRef.isValid()) {
            return;
        }

        PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
        UUIDComponent uuidComponent = chunk.getComponent(index, UUIDComponent.getComponentType());
        TransformComponent transform = chunk.getComponent(index, TransformComponent.getComponentType());
        InventoryComponent.Armor armor = chunk.getComponent(index, InventoryComponent.Armor.getComponentType());

        if (playerRef == null || uuidComponent == null || transform == null || armor == null) {
            return;
        }

        UUID uuid = uuidComponent.getUuid();
        MobilityState state = states.computeIfAbsent(uuid, ignored -> new MobilityState());

        double x = transform.getPosition().x;
        double y = transform.getPosition().y;
        double z = transform.getPosition().z;

        if (!state.initialized) {
            state.initialize(x, y, z);
            return;
        }

        double dx = x - state.lastX;
        double dy = y - state.lastY;
        double dz = z - state.lastZ;

        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double horizontalSpeed = horizontalDistance / dt;
        double verticalSpeed = dy / dt;

        state.horizontalBoostCooldown = Math.max(0.0F, state.horizontalBoostCooldown - dt);
        state.jumpBoostCooldown = Math.max(0.0F, state.jumpBoostCooldown - dt);

        boolean hasRigEquipped = hasEngineeringRigEquipped(armor);

        if (hasRigEquipped) {
            applyHorizontalBoostIfNeeded(playerRef, state, dx, dz, horizontalDistance, horizontalSpeed, verticalSpeed);
            applyJumpBoostIfNeeded(playerRef, state, verticalSpeed);
        } else {
            state.horizontalBoostCooldown = 0.0F;
            state.jumpBoostCooldown = 0.0F;
        }

        state.lastX = x;
        state.lastY = y;
        state.lastZ = z;
        state.lastVerticalSpeed = verticalSpeed;
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }

    private static void applyHorizontalBoostIfNeeded(
            @Nonnull PlayerRef playerRef,
            @Nonnull MobilityState state,
            double dx,
            double dz,
            double horizontalDistance,
            double horizontalSpeed,
            double verticalSpeed
    ) {
        if (state.horizontalBoostCooldown > 0.0F) {
            return;
        }

        if (horizontalDistance <= 1.0E-6D) {
            return;
        }

        if (horizontalSpeed < MIN_HORIZONTAL_SPEED_TO_BOOST) {
            return;
        }

        if (horizontalSpeed > MAX_HORIZONTAL_SPEED_TO_BOOST) {
            return;
        }

        if (Math.abs(verticalSpeed) > MAX_VERTICAL_SPEED_FOR_GROUND_BOOST) {
            return;
        }

        double dirX = dx / horizontalDistance;
        double dirZ = dz / horizontalDistance;

        sendVelocity(
                playerRef,
                (float) (dirX * HORIZONTAL_BOOST_AMOUNT),
                0.0F,
                (float) (dirZ * HORIZONTAL_BOOST_AMOUNT),
                HORIZONTAL_VELOCITY_CONFIG
        );

        state.horizontalBoostCooldown = HORIZONTAL_BOOST_INTERVAL_SECONDS;
    }

    private static void applyJumpBoostIfNeeded(
            @Nonnull PlayerRef playerRef,
            @Nonnull MobilityState state,
            double verticalSpeed
    ) {
        if (state.jumpBoostCooldown > 0.0F) {
            return;
        }

        boolean startedJumping =
                verticalSpeed >= JUMP_START_VERTICAL_SPEED
                        && state.lastVerticalSpeed <= PREVIOUS_VERTICAL_SPEED_MAX_FOR_JUMP;

        if (!startedJumping) {
            return;
        }

        sendVelocity(
                playerRef,
                0.0F,
                JUMP_BOOST_AMOUNT,
                0.0F,
                JUMP_VELOCITY_CONFIG
        );

        state.jumpBoostCooldown = JUMP_BOOST_COOLDOWN_SECONDS;
    }

    private static void sendVelocity(
            @Nonnull PlayerRef playerRef,
            float x,
            float y,
            float z,
            @Nullable VelocityConfig config
    ) {
        playerRef.getPacketHandler().writeNoCache(
                new ChangeVelocity(
                        x,
                        y,
                        z,
                        ChangeVelocityType.Add,
                        config == null ? null : config.clone()
                )
        );
    }

    private static boolean hasEngineeringRigEquipped(@Nonnull InventoryComponent.Armor armor) {
        return isEngineeringRig(getArmorItemId(armor, ItemArmorSlot.Head))
                || isEngineeringRig(getArmorItemId(armor, ItemArmorSlot.Chest))
                || isEngineeringRig(getArmorItemId(armor, ItemArmorSlot.Hands))
                || isEngineeringRig(getArmorItemId(armor, ItemArmorSlot.Legs));
    }

    @Nonnull
    private static String getArmorItemId(
            @Nonnull InventoryComponent.Armor armor,
            @Nonnull ItemArmorSlot slot
    ) {
        ItemContainer container = armor.getInventory();
        if (container == null) {
            return "";
        }

        short slotIndex = (short) slot.getValue();
        if (slotIndex < 0 || slotIndex >= container.getCapacity()) {
            return "";
        }

        ItemStack stack = container.getItemStack(slotIndex);
        if (ItemStack.isEmpty(stack)) {
            return "";
        }

        return safe(stack.getItemId());
    }

    private static boolean isEngineeringRig(@Nullable String itemIdRaw) {
        String itemId = safe(itemIdRaw).toLowerCase(Locale.ROOT);

        if (itemId.isEmpty()) {
            return false;
        }

        for (String pattern : ENGINEERING_RIG_PATTERNS) {
            if (itemId.contains(pattern)) {
                return true;
            }
        }

        return false;
    }

    @Nonnull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static final class MobilityState {
        private boolean initialized;

        private double lastX;
        private double lastY;
        private double lastZ;

        private double lastVerticalSpeed;

        private float horizontalBoostCooldown;
        private float jumpBoostCooldown;

        private void initialize(double x, double y, double z) {
            this.initialized = true;
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
            this.lastVerticalSpeed = 0.0D;
            this.horizontalBoostCooldown = 0.0F;
            this.jumpBoostCooldown = 0.0F;
        }
    }
}