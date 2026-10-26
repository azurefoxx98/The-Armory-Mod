package me.ladypaladra.thearmorymod.stats.systems;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.stats.ArmoryStatIds;
import me.ladypaladra.thearmorymod.stats.JumpForceState;

import javax.annotation.Nonnull;

/**
 * Makes a player jump higher or lower while they wear armour carrying the jump height stat. The
 * jump from the ground and the jump out of water change by the same amount.
 */
public final class JumpHeightModifierSystem extends MovementStatSystem<JumpForceState> {

    public JumpHeightModifierSystem(@Nonnull ComponentType<EntityStore, JumpForceState> stateType) {
        super(ArmoryStatIds.JUMP_HEIGHT, stateType, JumpForceState::new);
    }
}
