package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.camera.CameraEffect;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Everything a roll shows and says: the clack of each bounce, the awakening of the face that lands on top, the chat
 * line, the big carved number, and the band's own touch, up to the Avatar's Favour and the Veil. All of it is
 * cosmetic.
 *
 * <p>Every particle and sound is named here and looked up once the assets load. One that is missing is skipped with a
 * single warning, so a renamed asset costs a sparkle, never a roll.</p>
 */
public final class DiceShow {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Blocks within which players read the roll in chat, and see the big number.
     */
    static final double CHAT_RANGE = 24.0;
    static final double NUMBER_RANGE = 32.0;

    /**
     * Ticks the big number stays: longer for the extremes, longest for a D100's.
     */
    static final int NUMBER_LIFE = 90;
    static final int EXTREME_LIFE = 135;
    static final int GRAND_LIFE = 180;

    static final String ROLLED = "server.armoryDice.rolled";
    static final String ROLLED_MANY = "server.armoryDice.rolledMany";

    static final String SOUND_BOUNCE = "SFX_Stone_Hit";
    static final String SOUND_AWAKEN = "SFX_MemoryMote";
    static final String SOUND_PICK_UP = "SFX_Player_Pickup_Item";
    static final String SOUND_FAVOUR = "SFX_Avatar_Powers_Enable";
    static final String SOUND_FAVOUR_NEAR = "SFX_Runebinders_Lattice_Unlock_Local";
    static final String SOUND_VEIL = "SFX_Emit_Void_Whispers";
    static final String SOUND_VEIL_STRIKE = "SFX_Emit_Dungeon_Goblin_Void_Strike";
    static final String SHAKE_VEIL = "Impact_Light";

    /**
     * Blocks within which the Veil shakes the screen, and the Favour's stinger plays.
     */
    static final double SHAKE_RANGE = 8.0;
    static final double STINGER_RANGE = 16.0;

    private static final Set<String> warned = new HashSet<>();

    private DiceShow() {
    }

    /**
     * Checks every effect the shows name, once the assets are loaded, and reports each that is missing.
     */
    static void resolve() {
        for (String sound : List.of(SOUND_BOUNCE, SOUND_AWAKEN, SOUND_PICK_UP, SOUND_FAVOUR, SOUND_FAVOUR_NEAR,
                SOUND_VEIL, SOUND_VEIL_STRIKE)) {
            if (SoundEvent.getAssetMap().getIndex(sound) == SoundEvent.EMPTY_ID) {
                warnOnce("sound " + sound);
            }
        }
        if (CameraEffect.getAssetMap().getAsset(SHAKE_VEIL) == null) {
            warnOnce("camera effect " + SHAKE_VEIL);
        }
    }

    static void bounce(@Nonnull DieShape shape, @Nonnull Vector3d at, double struck,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (struck < 1.0) {
            return;
        }
        float volume = (float) Math.max(0.15, Math.min(0.8, struck / 7.0));
        float pitch = 1.35f + ThreadLocalRandom.current().nextFloat() * 0.45f;
        sound(SOUND_BOUNCE, at, volume, pitch, commandBuffer);
        particles("Armory_Dice_Bounce_" + colour(shape), at, 1.0f, commandBuffer);
    }

    static void awaken(@Nonnull DieShape shape, @Nonnull Vector3d spot, @Nonnull DieShape.Face face,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Vector3d top = new Vector3d(spot).add(0.0, face.restHeight(), 0.0);
        particles("Armory_Dice_Awaken_" + colour(shape), top, 1.0f, commandBuffer);
        sound(SOUND_AWAKEN, top, 0.55f, 1.15f, commandBuffer);
    }

    static void pickedUp(@Nonnull Vector3d at, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        sound(SOUND_PICK_UP, at, 0.8f, 1.0f, commandBuffer);
    }

    /**
     * Reads a finished throw out: the chat line, then for each item its number and its band's show.
     */
    static void announce(@Nonnull ThrowGroup group, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        List<ThrowGroup.Roll> rolls = group.rolls();
        if (rolls.isEmpty()) {
            return;
        }
        DieType type = group.type();
        DiceCatalog catalog = DiceModule.catalog();
        DieShape lead = catalog != null ? catalog.shapesOf(type).get(0) : null;
        chat(group, rolls, commandBuffer);
        for (ThrowGroup.Roll roll : rolls) {
            RollBand band = RollBand.of(roll.value(), type.sides());
            if (lead != null) {
                number(roll, band, lead, type, commandBuffer);
                bandShow(roll.at(), band, RollBand.Tier.of(type), lead, commandBuffer);
            }
        }
    }

    private static void chat(@Nonnull ThrowGroup group, @Nonnull List<ThrowGroup.Roll> rolls,
                             @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        String die = group.type().name();
        Message line;
        if (rolls.size() == 1) {
            line = Message.translation(ROLLED).param("player", group.ownerName()).param("die", die)
                    .param("result", rolls.get(0).value());
        } else {
            StringJoiner each = new StringJoiner(", ");
            int total = 0;
            for (ThrowGroup.Roll roll : rolls) {
                each.add(Integer.toString(roll.value()));
                total += roll.value();
            }
            line = Message.translation(ROLLED_MANY).param("player", group.ownerName()).param("count", rolls.size())
                    .param("die", die).param("results", each.toString()).param("total", total);
        }
        Set<Ref<EntityStore>> told = new HashSet<>();
        for (Ref<EntityStore> ref : playersNear(rolls.get(0).at(), CHAT_RANGE, commandBuffer)) {
            told.add(ref);
            send(ref, line, commandBuffer);
        }
        // The thrower always reads their own roll, however far the die went.
        Ref<EntityStore> thrower = commandBuffer.getExternalData().getRefFromUUID(group.owner());
        if (thrower != null && thrower.isValid() && !told.contains(thrower)) {
            send(thrower, line, commandBuffer);
        }
    }

    /**
     * Raises the big number over a roll, one set of carved tiles per player near, each facing its player.
     */
    private static void number(@Nonnull ThrowGroup.Roll roll, @Nonnull RollBand band, @Nonnull DieShape lead,
                               @Nonnull DieType type, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        String digits = Integer.toString(roll.value());
        String texture = band.numberTexture(lead.colour());
        int life = type == DieType.D100 ? GRAND_LIFE
                : band == RollBand.FAVOUR || band == RollBand.VEIL ? EXTREME_LIFE : NUMBER_LIFE;
        Vector3d anchor = new Vector3d(roll.at()).add(0.0, 0.45, 0.0);
        for (Ref<EntityStore> viewer : playersNear(roll.at(), NUMBER_RANGE, commandBuffer)) {
            PlayerRef player = commandBuffer.getComponent(viewer, PlayerRef.getComponentType());
            if (player == null) {
                continue;
            }
            for (int place = 0; place < digits.length(); place++) {
                String asset = "Armory_Dice_Number_" + digits.charAt(place);
                Model model = NumberTileSystems.tileModel(asset, texture, NumberTileSystems.SCALE * 0.35f);
                if (model == null) {
                    warnOnce("number model " + asset);
                    return;
                }
                Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
                holder.addComponent(TransformComponent.getComponentType(),
                        new TransformComponent(anchor, new Rotation3f()));
                holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
                holder.addComponent(NetworkId.getComponentType(),
                        new NetworkId(commandBuffer.getExternalData().takeNextNetworkId()));
                holder.addComponent(DiceModule.tileType(), new NumberTile(player.getUuid(), anchor, place,
                        digits.length(), asset, texture, band, life));
                holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType());
                commandBuffer.addEntity(holder, AddReason.SPAWN);
            }
        }
    }

    private static void bandShow(@Nonnull Vector3d at, @Nonnull RollBand band, @Nonnull RollBand.Tier tier,
                                 @Nonnull DieShape lead, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        String colour = colour(lead);
        switch (band) {
            case FAVOUR -> {
                particles("Armory_Dice_Favour_" + tier.artName() + "_" + colour, at, 1.0f, commandBuffer);
                if (tier != RollBand.Tier.MINOR) {
                    sound(SOUND_FAVOUR, at, 1.0f, 1.0f, commandBuffer);
                    int stinger = SoundEvent.getAssetMap().getIndex(SOUND_FAVOUR_NEAR);
                    if (stinger != SoundEvent.EMPTY_ID) {
                        for (Ref<EntityStore> ref : playersNear(at, STINGER_RANGE, commandBuffer)) {
                            PlayerRef player = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
                            if (player != null) {
                                SoundUtil.playSoundEvent2dToPlayer(player, stinger, SoundCategory.SFX);
                            }
                        }
                    }
                } else {
                    sound(SOUND_AWAKEN, at, 0.9f, 1.4f, commandBuffer);
                }
            }
            case VEIL -> {
                particles("Armory_Dice_Veil_" + tier.artName(), at, 1.0f, commandBuffer);
                sound(SOUND_VEIL, at, 0.9f, 0.9f, commandBuffer);
                if (tier != RollBand.Tier.MINOR) {
                    sound(SOUND_VEIL_STRIKE, at, 0.8f, 1.0f, commandBuffer);
                    shake(at, tier == RollBand.Tier.GRAND ? 0.8f : 0.5f, commandBuffer);
                }
            }
            case MIDDLE -> {
            }
            default -> particles("Armory_Dice_Band_" + band.artName() + "_" + colour, at, 1.0f, commandBuffer);
        }
    }

    private static void shake(@Nonnull Vector3d at, float strength, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        CameraEffect effect = CameraEffect.getAssetMap().getAsset(SHAKE_VEIL);
        if (effect == null) {
            return;
        }
        for (Ref<EntityStore> ref : playersNear(at, SHAKE_RANGE, commandBuffer)) {
            PlayerRef player = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
            TransformComponent where = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
            if (player == null || where == null) {
                continue;
            }
            double falloff = 1.0 - where.getPosition().distanceSquared(at) / (SHAKE_RANGE * SHAKE_RANGE);
            player.getPacketHandler().write(effect.createCameraShakePacket((float) (strength * Math.max(0.2, falloff))));
        }
    }

    private static void particles(@Nonnull String name, @Nonnull Vector3d at, float scale,
                                  @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (ParticleSystem.getAssetMap().getAsset(name) == null) {
            warnOnce("particle system " + name);
            return;
        }
        ParticleUtil.spawnParticleEffect(name, at, 0.0f, 0.0f, 0.0f, scale, 0.0f, commandBuffer);
    }

    private static void sound(@Nonnull String name, @Nonnull Vector3d at, float volume, float pitch,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        int index = SoundEvent.getAssetMap().getIndex(name);
        if (index == SoundEvent.EMPTY_ID) {
            warnOnce("sound " + name);
            return;
        }
        SoundUtil.playSoundEvent3d(index, SoundCategory.SFX, at.x, at.y, at.z, volume, pitch, commandBuffer);
    }

    @Nonnull
    private static List<Ref<EntityStore>> playersNear(@Nonnull Vector3d at, double range,
                                                      @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        var players = commandBuffer.getResource(EntityModule.get().getPlayerSpatialResourceType());
        List<Ref<EntityStore>> found = SpatialResource.getThreadLocalReferenceList();
        players.getSpatialStructure().collect(at, range, found);
        // Copied: the engine's list is shared by every caller on this thread.
        return new ArrayList<>(found);
    }

    private static void send(@Nonnull Ref<EntityStore> ref, @Nonnull Message line,
                             @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        PlayerRef player = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        if (player != null) {
            player.sendMessage(line);
        }
    }

    @Nonnull
    private static String colour(@Nonnull DieShape shape) {
        return RollBand.capitalise(shape.colour());
    }

    private static void warnOnce(@Nonnull String what) {
        synchronized (warned) {
            if (warned.add(what)) {
                LOGGER.atWarning().log("Dice show: %s is missing and is skipped", what);
            }
        }
    }
}
