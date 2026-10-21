package me.ladypaladra.thearmorymod.showcase;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.RoleUtils;
import com.hypixel.hytale.server.npc.util.InventoryHelper;
import it.unimi.dsi.fastutil.Pair;
import me.ladypaladra.thearmorymod.TheArmoryMod;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The figure a showcase stand puts on display: an NPC with its own role and model, dressed from the
 * stand's names. Everything here carries names of its own, so a server that also runs the Armor Stand
 * mod keeps both apart. That mod finds its figures by its own role names and never sees these.
 */
public final class ShowcaseFigure {

    public static final String ROLE = "Armory_Showcase_Figure";

    /** The model's pose that pins every bone at rest, played in the highest animation slot. */
    public static final String HOLD_ANIMATION = "Hold";

    private ShowcaseFigure() {
    }

    /**
     * Builds the figure for a stand at a block position. Runs on the world thread.
     *
     * @return the figure, or null when it could not be built or fully dressed, in which case
     * nothing is left standing
     */
    @Nullable
    static Ref<EntityStore> spawn(
            @Nonnull World world,
            @Nonnull ShowcaseFigureStand stand,
            int x,
            int y,
            int z,
            float blockYaw
    ) {
        NPCPlugin npcPlugin = NPCPlugin.get();

        if (npcPlugin == null || !npcPlugin.hasRoleName(ROLE)) {
            TheArmoryMod.LOGGER.atWarning().log("Showcase figure role %s is not registered, the stand at %d,%d,%d stays empty", ROLE, x, y, z);
            return null;
        }

        // A stand's figure faces away from the stand block's own front, as the Armor Stand mod's do,
        // so the turns the showcase prefabs were laid out with face the same way.
        Rotation3f rotation = new Rotation3f(0.0F, blockYaw + (float) Math.PI, 0.0F);
        Vector3d position = new Vector3d(x + 0.5, y, z + 0.5);
        Store<EntityStore> store = world.getEntityStore().getStore();

        Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(
                store, npcPlugin.getIndex(ROLE), position, rotation, null, ShowcaseFigure::prepareHolder, null);
        Ref<EntityStore> figureRef = spawned != null ? spawned.first() : null;
        NPCEntity figure = figureRef != null && figureRef.isValid() ? spawned.second() : null;

        if (figure == null) {
            TheArmoryMod.LOGGER.atWarning().log("Showcase figure could not be spawned at %d,%d,%d", x, y, z);
            return null;
        }

        if (!neutralize(figureRef, store) || !dress(figure, figureRef, store, stand)) {
            remove(figureRef);
            TheArmoryMod.LOGGER.atWarning().log("Showcase figure at %d,%d,%d could not be set up, so it is not shown", x, y, z);
            return null;
        }

        markEquipmentOutdated(figureRef, store);

        // A weapon in the hand brings its own stance, which the client plays over the whole body
        // and pushes the armour off the stand. The model's still pose in the highest slot overrides it.
        try {
            figure.playAnimation(figureRef, AnimationSlot.ServerAction, HOLD_ANIMATION, true, store);
        } catch (Exception e) {
            TheArmoryMod.LOGGER.atWarning().withCause(e).log("Showcase figure could not be held still, it may take its weapon's stance");
        }

        return figureRef;
    }

    // Called by the engine right before the entity joins the store, so the figure never exists for
    // an instant either savable or unprotected.
    private static void prepareHolder(NPCEntity npc, Holder<EntityStore> holder, Store<EntityStore> store) {
        holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());
        holder.addComponent(Shield.getComponentType(), new Shield());
    }

    private static boolean dress(NPCEntity figure, Ref<EntityStore> figureRef, Store<EntityStore> store, ShowcaseFigureStand stand) {
        try {
            for (String armor : stand.getArmor()) {
                if (armor != null && !armor.isEmpty()) {
                    RoleUtils.setArmor(figureRef, figure, armor, store);
                }
            }

            if (stand.getMainHand() != null && !stand.getMainHand().isEmpty()) {
                RoleUtils.setItemInHand(figureRef, figure, stand.getMainHand(), store);
            }

            if (stand.getOffHand() != null && !stand.getOffHand().isEmpty()) {
                InventoryHelper.setOffHandItem(figureRef, stand.getOffHand(), (byte) 0, store);
            }

            return true;
        } catch (Exception e) {
            TheArmoryMod.LOGGER.atWarning().withCause(e).log("Showcase figure could not be dressed");
            return false;
        }
    }

    // The figure is scenery. It answers no interaction of its own and stands idle, so the showcase's
    // own volumes are the only thing a click on it reaches.
    private static boolean neutralize(Ref<EntityStore> figureRef, Store<EntityStore> store) {
        try {
            InteractionModule interactionModule = InteractionModule.get();

            if (interactionModule != null) {
                InteractionManager manager = store.getComponent(figureRef, interactionModule.getInteractionManagerComponent());

                if (manager != null) {
                    manager.clear();
                }
            }

            MovementStatesComponent movement = store.getComponent(figureRef, MovementStatesComponent.getComponentType());

            if (movement != null) {
                MovementStates states = new MovementStates();
                states.idle = true;
                states.horizontalIdle = true;
                states.onGround = true;
                movement.setMovementStates(states);

                if (movement.getSentMovementStates() == null) {
                    movement.setSentMovementStates(new MovementStates());
                }
            }

            return true;
        } catch (Exception e) {
            TheArmoryMod.LOGGER.atWarning().withCause(e).log("Showcase figure could not be made inert");
            return false;
        }
    }

    // Equipment set straight after a spawn is otherwise not sent to players who already see the figure.
    private static void markEquipmentOutdated(Ref<EntityStore> figureRef, Store<EntityStore> store) {
        InventoryComponent.Armor armor = store.getComponent(figureRef, InventoryComponent.Armor.getComponentType());

        if (armor != null) {
            armor.setOutdatedEquipment(true);
        }

        InventoryComponent.Hotbar hotbar = store.getComponent(figureRef, InventoryComponent.Hotbar.getComponentType());

        if (hotbar != null) {
            hotbar.setOutdatedEquipment(true);
        }

        InventoryComponent.Utility utility = store.getComponent(figureRef, InventoryComponent.Utility.getComponentType());

        if (utility != null) {
            utility.setOutdatedEquipment(true);
        }
    }

    static boolean isAlive(@Nullable Ref<EntityStore> figureRef) {
        return figureRef != null && figureRef.isValid();
    }

    static void remove(@Nullable Ref<EntityStore> figureRef) {
        if (isAlive(figureRef)) {
            figureRef.getStore().removeEntity(figureRef, RemoveReason.REMOVE);
        }
    }

    /**
     * Marks a showcase figure so the damage filter can find it. Never saved, because the figure is not.
     */
    public static final class Shield implements Component<EntityStore> {

        @Nonnull
        public static final BuilderCodec<Shield> CODEC = BuilderCodec.builder(Shield.class, Shield::new).build();

        private static ComponentType<EntityStore, Shield> componentType;

        public static void setComponentType(@Nonnull ComponentType<EntityStore, Shield> type) {
            componentType = type;
        }

        public static ComponentType<EntityStore, Shield> getComponentType() {
            return componentType;
        }

        @Nonnull
        @Override
        public Component<EntityStore> clone() {
            return new Shield();
        }
    }
}
