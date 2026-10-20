package me.ladypaladra.thearmorymod.powers;

import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Item powers that the engine has no data field for: resistances carried by stats, gloves that
 * make a bare fist hit harder, a shield that reflects, a pack that bursts, breathing underwater,
 * the ore pack, and gear that gives more mana or health at night.
 *
 * <p>Swim speed sits with the other movement stats in the stats module.</p>
 */
public final class PowersModule {

    private PowersModule() {
    }

    public static void register(@Nonnull JavaPlugin plugin) {
        // Interaction types must exist before any asset naming them is decoded.
        plugin.getCodecRegistry(Interaction.CODEC).register(
                "ArmoryReflectDamage",
                ReflectDamageInteraction.class,
                ReflectDamageInteraction.CODEC
        );
        plugin.getCodecRegistry(Interaction.CODEC).register(
                "ArmorySporeBurst",
                SporeBurstInteraction.class,
                SporeBurstInteraction.CODEC
        );

        ComponentRegistryProxy<EntityStore> registry = plugin.getEntityStoreRegistry();
        registry.registerSystem(new StatResistanceSystem());
        registry.registerSystem(new UnarmedDamageSystem());
        registry.registerSystem(new SporeBurstSystem());
        registry.registerSystem(new WaterBreathingSystems.Check());
        registry.registerSystem(new WaterBreathingSystems.Recheck());
        registry.registerSystem(new OrePackSystems.MiningSpeed());
        registry.registerSystem(new OrePackSystems.OreFind());
        registry.registerSystem(new NightBonusSystem());
    }
}
