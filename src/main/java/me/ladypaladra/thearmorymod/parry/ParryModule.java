package me.ladypaladra.thearmorymod.parry;

import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.parry.components.ParryComponent;
import me.ladypaladra.thearmorymod.parry.components.StunComponent;
import me.ladypaladra.thearmorymod.parry.systems.BlockTrackingSystem;
import me.ladypaladra.thearmorymod.parry.systems.PlayerParryAdderSystem;
import me.ladypaladra.thearmorymod.parry.systems.SimpleParrySystem;
import me.ladypaladra.thearmorymod.parry.systems.StunSystem;

public class ParryModule {

    private ParryModule() { }

    private static ComponentType<EntityStore, ParryComponent> parryComponentType;
    private static ComponentType<EntityStore, StunComponent> stunComponentType;

    public static void register(JavaPlugin plugin) {
        ComponentRegistryProxy<EntityStore> registry = plugin.getEntityStoreRegistry();

        parryComponentType = registry.registerComponent(ParryComponent.class, ParryComponent::new);
        stunComponentType = registry.registerComponent(StunComponent.class, StunComponent::new);

        registry.registerSystem(new PlayerParryAdderSystem(parryComponentType));
        registry.registerSystem(new BlockTrackingSystem(parryComponentType));
        registry.registerSystem(new SimpleParrySystem(parryComponentType));
        registry.registerSystem(new StunSystem(stunComponentType));
    }

    public static ComponentType<EntityStore, ParryComponent> getParryComponentType() {
        return parryComponentType;
    }
    public static ComponentType<EntityStore, StunComponent> getStunComponentType() {
        return stunComponentType;
    }
}
