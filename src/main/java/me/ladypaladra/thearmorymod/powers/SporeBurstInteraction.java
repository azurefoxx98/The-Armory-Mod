package me.ladypaladra.thearmorymod.powers;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.validation.Validators;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.ExplosionConfig;
import com.hypixel.hytale.server.core.entity.ExplosionUtils;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.ExplodeInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A burst around an entity that never touches the entity itself.
 *
 * <p>The vanilla Explode interaction pushes everyone in range, its own user included. This one
 * runs the same engine explosion with the user as the ignored entity. Its config is the
 * engine's own explosion format, so a data file decides the radius, push, particles and sound.
 * The engine applies entity damage only when it is above zero, so a config with no damage and
 * no block damage is a pure push.</p>
 */
public final class SporeBurstInteraction extends SimpleInstantInteraction {

    @Nonnull
    public static final BuilderCodec<SporeBurstInteraction> CODEC = BuilderCodec
            .builder(SporeBurstInteraction.class, SporeBurstInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Performs an explosion around the user that ignores the user.")
            .append(
                    new KeyedCodec<>("Config", ExplosionConfig.CODEC),
                    (interaction, value) -> interaction.config = value,
                    interaction -> interaction.config
            )
            .addValidator(Validators.nonNull())
            .documentation("The explosion, in the engine's explosion format.")
            .add()
            .build();

    @Nullable
    private ExplosionConfig config;

    public SporeBurstInteraction() {
    }

    @Nonnull
    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(
            @Nonnull InteractionType type,
            @Nonnull InteractionContext context,
            @Nonnull CooldownHandler cooldownHandler
    ) {
        context.getState().state = InteractionState.Finished;

        CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
        if (commandBuffer != null) {
            burst(context.getEntity(), commandBuffer);
        }
    }

    /**
     * Bursts around the entity. Safe to call from a damage system, since a config without
     * damage queues no new damage.
     */
    public void burst(@Nonnull Ref<EntityStore> user, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (config == null || !user.isValid()) {
            return;
        }

        TransformComponent transform = commandBuffer.getComponent(user, TransformComponent.getComponentType());
        World world = commandBuffer.getExternalData().getWorld();
        if (transform == null || world == null) {
            return;
        }

        ExplosionUtils.performExplosion(
                ExplodeInteraction.DAMAGE_SOURCE_EXPLOSION,
                new Vector3d(transform.getPosition()),
                new Rotation3f(0, 0, 0),
                config,
                user,
                commandBuffer,
                world.getChunkStore().getStore()
        );
    }
}
