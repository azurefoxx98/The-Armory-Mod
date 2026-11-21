package me.ladypaladra.thearmorymod;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a server owner can set. The file, config.json, appears in the mod's data folder on the first start.
 */
public final class ArmoryConfig {

    public static final BuilderCodec<ArmoryConfig> CODEC = BuilderCodec.builder(ArmoryConfig.class, ArmoryConfig::new)
            .append(new KeyedCodec<>("DiceRollsInChat", Codec.BOOLEAN),
                    (c, v) -> c.diceRollsInChat = v, c -> c.diceRollsInChat)
            .documentation("True writes every dice roll in chat, to the thrower and the players near it. "
                    + "The number rising over a landed die shows either way.").add()
            .append(new KeyedCodec<>("MaxDicePerPlayer", Codec.INTEGER),
                    (c, v) -> c.maxDicePerPlayer = v, c -> c.maxDicePerPlayer)
            .documentation("How many dice one player may have out in the world at once, flying or lying. A throw past "
                    + "it brings the player's oldest lying dice back to them. -1 is no limit, 0 is no dice thrown. "
                    + "Players with the permission thearmory.dice.unlimited, operators included, have no limit.").add()
            .append(new KeyedCodec<>("DiceLimitsByPermission", new MapCodec<>(Codec.INTEGER, LinkedHashMap::new)),
                    (c, v) -> c.diceLimitsByPermission = v != null ? v : new LinkedHashMap<>(),
                    c -> c.diceLimitsByPermission)
            .documentation("More limits, each for the players who hold its permission: {\"vip\": 50} gives 50 dice to "
                    + "players with thearmory.dice.limit.vip. A player gets the highest limit they are entitled to.").add()
            .build();

    // Off by default, so a busy server's chat is not filled with rolls.
    private boolean diceRollsInChat = false;

    // Enough for any roll at a table, and few enough that nobody can fill a world with dice.
    private int maxDicePerPlayer = 10;

    private Map<String, Integer> diceLimitsByPermission = new LinkedHashMap<>();

    public boolean diceRollsInChat() {
        return diceRollsInChat;
    }

    public int maxDicePerPlayer() {
        return maxDicePerPlayer;
    }

    public Map<String, Integer> diceLimitsByPermission() {
        return diceLimitsByPermission;
    }
}
