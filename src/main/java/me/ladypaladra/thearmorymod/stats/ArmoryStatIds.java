package me.ladypaladra.thearmorymod.stats;

import java.util.List;

public final class ArmoryStatIds {

    private ArmoryStatIds() {
    }

    public static final String ARROW_DAMAGE_BONUS = "Arrow_Damage_Bonus";

    public static final String MOVE_SPEED = "Move_Speed";

    public static final String JUMP_HEIGHT = "Jump_Height";

    public static final String SWIM_SPEED = "TA_Swim_Speed";

    public static final String FIRE_RESISTANCE = "TA_Fire_Resistance";

    public static final String BLAST_RESISTANCE = "TA_Blast_Resistance";

    public static final String MINING_SPEED = "TA_Mining_Speed";

    public static final String ORE_FIND = "TA_Ore_Find";

    public static final String UNARMED_DAMAGE = "TA_Unarmed_Damage";

    public static final List<String> ALL = List.of(
            ARROW_DAMAGE_BONUS,
            MOVE_SPEED,
            JUMP_HEIGHT,
            SWIM_SPEED,
            FIRE_RESISTANCE,
            BLAST_RESISTANCE,
            MINING_SPEED,
            ORE_FIND,
            UNARMED_DAMAGE
    );
}
