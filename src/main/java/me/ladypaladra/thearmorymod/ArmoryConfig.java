package me.ladypaladra.thearmorymod;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * What a server owner can set. The file, config.json, appears in the mod's data folder on the first start.
 */
public final class ArmoryConfig {

    public static final BuilderCodec<ArmoryConfig> CODEC = BuilderCodec.builder(ArmoryConfig.class, ArmoryConfig::new)
            .append(new KeyedCodec<>("DiceRollsInChat", Codec.BOOLEAN),
                    (c, v) -> c.diceRollsInChat = v, c -> c.diceRollsInChat)
            .documentation("True writes every dice roll in chat, to the thrower and the players near it. "
                    + "The number rising over a landed die shows either way.").add()
            .build();

    // Off by default, so a busy server's chat is not filled with rolls.
    private boolean diceRollsInChat = false;

    public boolean diceRollsInChat() {
        return diceRollsInChat;
    }
}
