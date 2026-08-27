package me.ladypaladra.thearmorymod.stats;

import java.util.List;

public final class ArmoryStatIds {

    private ArmoryStatIds() {
    }

    public static final String ARROW_DAMAGE_BONUS = "Arrow_Damage_Bonus";

    public static final String MOVE_SPEED = "Move_Speed";

    public static final String JUMP_HEIGHT = "Jump_Height";

    public static final List<String> ALL = List.of(
            ARROW_DAMAGE_BONUS,
            MOVE_SPEED,
            JUMP_HEIGHT
    );
}
