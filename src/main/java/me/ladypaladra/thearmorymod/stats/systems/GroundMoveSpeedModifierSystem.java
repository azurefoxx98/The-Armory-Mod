package me.ladypaladra.thearmorymod.stats.systems;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.stats.ArmoryStatIds;
import me.ladypaladra.thearmorymod.stats.GroundSpeedState;

import javax.annotation.Nonnull;

/**
 * Makes a player move faster or slower on the ground while they wear armour carrying the move
 * speed stat. Walking, running, crouching and sprinting all change by the same amount, so a
 * slowing piece is felt at every pace and not only while sprinting.
 */
public final class GroundMoveSpeedModifierSystem extends MovementStatSystem<GroundSpeedState> {

    public GroundMoveSpeedModifierSystem(@Nonnull ComponentType<EntityStore, GroundSpeedState> stateType) {
        super(ArmoryStatIds.MOVE_SPEED, stateType, GroundSpeedState::new);
    }
}
