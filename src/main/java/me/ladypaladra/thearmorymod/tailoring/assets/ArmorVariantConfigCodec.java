package me.ladypaladra.thearmorymod.tailoring.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;

import javax.annotation.Nonnull;

public final class ArmorVariantConfigCodec {

    @Nonnull
    public static final BuilderCodec<ArmorVariantConfig> CODEC;

    private ArmorVariantConfigCodec() {
    }

    static {
        var builder = BuilderCodec.builder(ArmorVariantConfig.class, ArmorVariantConfig::new);

        builder.append(
                        new KeyedCodec<>("Items", new ArrayCodec(Codec.STRING, String[]::new)),
                        (config, items) -> config.itemIds = items == null ? new String[0] : items,
                        config -> config.itemIds
                )
                .documentation("All armor item ids that belong to the same visual variant group.")
                .add();

        CODEC = builder.build();
    }
}