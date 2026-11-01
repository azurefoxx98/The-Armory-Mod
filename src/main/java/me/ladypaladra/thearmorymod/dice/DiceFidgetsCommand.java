package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * {@code /dicefidgets}: a tester's switch. On, a still hand holding a die plays every fidget in turn, a second apart,
 * instead of one every 12 to 24 s. Typed again, it is off. Operators only: the engine gives every command a
 * permission, and only operators hold it unless the command opts out.
 */
public final class DiceFidgetsCommand extends AbstractPlayerCommand {

    public DiceFidgetsCommand() {
        super("dicefidgets", "Play every dice fidget in turn, a second apart, to judge them (again to stop)");
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef player, @Nonnull World world) {
        boolean on = DiceFidgets.toggleShowcase(player.getUuid());
        context.sendMessage(Message.raw(on
                ? "Dice fidgets: every one in turn, a second apart. Hold a die and stand still."
                : "Dice fidgets: back to one every 12 to 24 seconds."));
    }
}
