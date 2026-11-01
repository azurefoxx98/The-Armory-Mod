package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.protocol.BlockParticleEvent;
import com.hypixel.hytale.protocol.BlockSoundEvent;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.blockparticle.config.BlockParticleSet;
import com.hypixel.hytale.server.core.asset.type.blocksound.config.BlockSoundSet;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Makes the ground answer a die: each strike plays the struck block's own sound and raises its own dust, a die falling
 * into water splashes with the fluid's own splash, and bubbles rise from it while it sinks. Every sound and particle is
 * the game's own, read from the block or fluid hit, so a die sounds on every block the way the block itself does,
 * including blocks of other mods.
 *
 * <p>It is the engine's own way of playing a block's sounds and dust (a falling block breaking does the same), from
 * the die's physics, which knows where each tick's hardest strike touched.</p>
 */
final class GroundFeel {

    /**
     * The bubbles a sinking die trails, one puff every this many ticks while it is under water.
     */
    static final String BUBBLES = "Armory_Dice_Bubbles";
    static final int BUBBLE_EVERY = 6;

    /**
     * How far past the contact point, into the struck block, the cell is read: the point can lie on a block's very face.
     */
    private static final double INTO = 0.05;

    private GroundFeel() {
    }

    /**
     * Plays the struck block's sound and dust for a strike at {@code point}, the surface facing {@code normal}.
     *
     * @return whether the block had a sound to play; if not, the caller plays the die's own clack
     */
    static boolean strike(@Nonnull World world, @Nonnull EngineDieWorld blocks, @Nonnull Vector3d point,
                          @Nonnull Vector3d normal, double struck, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                          @Nonnull RandomGenerator rng) {
        int x = (int) Math.floor(point.x - normal.x * INTO);
        int y = (int) Math.floor(point.y - normal.y * INTO);
        int z = (int) Math.floor(point.z - normal.z * INTO);
        int blockId = blocks.readCell(x, y, z);
        BlockType block = blocks.getBlockType();
        if (block == null || blockId == BlockType.EMPTY_ID || block.isUnknown()) {
            return false;
        }
        BlockSoundSet sounds = BlockSoundSet.getAssetMap().getAsset(block.getBlockSoundSetIndex());
        if (sounds == null) {
            return false;
        }
        int hit = sounds.getSoundEventIndices().getOrDefault(BlockSoundEvent.Hit, SoundEvent.EMPTY_ID);
        int land = sounds.getSoundEventIndices().getOrDefault(BlockSoundEvent.Land, SoundEvent.EMPTY_ID);
        GroundSound.Kind kind = GroundSound.kind(sounds.getId(), hit != SoundEvent.EMPTY_ID, land != SoundEvent.EMPTY_ID);
        if (kind == null) {
            return false;
        }
        SoundUtil.playSoundEvent3d(kind == GroundSound.Kind.HIT ? hit : land, SoundCategory.SFX, point.x, point.y,
                point.z, GroundSound.volume(struck), GroundSound.pitch(kind, rng), commandBuffer);
        if (GroundSound.dusts(struck)) {
            world.getNotificationHandler().sendBlockParticle(point.x, point.y, point.z, blockId,
                    GroundSound.heavy(struck) ? BlockParticleEvent.HardLand : BlockParticleEvent.Hit);
        }
        return true;
    }

    /**
     * Splashes a die that has just fallen into a fluid at {@code middle}: the fluid's own sound for something entering
     * it, and its own splash, the heavy one for a fast fall. Nothing for a fluid that names neither.
     */
    static void splash(@Nonnull EngineDieWorld blocks, @Nonnull Vector3d middle, double entrySpeed,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull RandomGenerator rng) {
        int x = (int) Math.floor(middle.x);
        int z = (int) Math.floor(middle.z);
        int y = (int) Math.floor(middle.y);
        // The die counts as in the water from the moment its lower half dips in, while its middle may still be in the
        // dry cell over the surface, as the physics measures it.
        double surface = blocks.fluidTop(x, y, z);
        if (Double.isNaN(surface)) {
            y--;
            surface = blocks.fluidTop(x, y, z);
        }
        blocks.readCell(x, y, z);
        Fluid fluid = blocks.getFluid();
        if (fluid == null || Double.isNaN(surface)) {
            return;
        }
        // The fluid keeps its sound and particle sets for the client; its packet is the one public place they show.
        var sent = fluid.toPacket();
        Vector3d at = new Vector3d(middle.x, surface, middle.z);
        BlockSoundSet sounds = BlockSoundSet.getAssetMap().getAsset(sent.blockSoundSetIndex);
        if (sounds != null) {
            int moveIn = sounds.getSoundEventIndices().getOrDefault(BlockSoundEvent.MoveIn, SoundEvent.EMPTY_ID);
            if (moveIn != SoundEvent.EMPTY_ID) {
                SoundUtil.playSoundEvent3d(moveIn, SoundCategory.SFX, at.x, at.y, at.z,
                        GroundSound.splashVolume(entrySpeed), 1.15f + rng.nextFloat() * 0.25f, commandBuffer);
            }
        }
        BlockParticleSet particles = sent.blockParticleSetId != null
                ? BlockParticleSet.getAssetMap().getAsset(sent.blockParticleSetId) : null;
        if (particles != null) {
            Map<BlockParticleEvent, String> systems = particles.getParticleSystemIds();
            String system = systems.get(entrySpeed >= GroundSound.HEAVY_SPLASH
                    ? BlockParticleEvent.HardLand : BlockParticleEvent.SoftLand);
            if (system == null) {
                system = systems.get(BlockParticleEvent.Hit);
            }
            if (system != null) {
                DiceShow.particles(system, at, 1.0f, commandBuffer);
            }
        }
    }

    /**
     * A puff of bubbles from a sinking die.
     */
    static void bubbles(@Nonnull Vector3d middle, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        DiceShow.particles(BUBBLES, middle, 1.0f, commandBuffer);
    }
}
