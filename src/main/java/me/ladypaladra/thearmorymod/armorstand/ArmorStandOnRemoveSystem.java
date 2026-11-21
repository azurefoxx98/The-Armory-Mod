package me.ladypaladra.thearmorymod.armorstand;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule.BlockStateInfo;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

public class ArmorStandOnRemoveSystem extends RefSystem<ChunkStore> {
   private static final HytaleLogger LOGGER = HytaleLogger.get("ArmorStand");
   private final ComponentType<ChunkStore, ArmorStandComponent> armorStandComponentType;
   private final ComponentType<ChunkStore, BlockStateInfo> blockStateInfoComponentType = BlockStateInfo.getComponentType();
   private final Query<ChunkStore> query;

   public ArmorStandOnRemoveSystem(ComponentType<ChunkStore, ArmorStandComponent> armorStandComponentType) {
      this.armorStandComponentType = armorStandComponentType;
      this.query = Query.and(new Query[]{this.armorStandComponentType, this.blockStateInfoComponentType});
   }

   public void onEntityAdded(@Nonnull Ref<ChunkStore> ref, @Nonnull AddReason reason, @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
      try {
         ItemContainerBlock containerBlock = commandBuffer.getComponent(ref, ItemContainerBlock.getComponentType());
         if (containerBlock == null) {
            return;
         }

         SimpleItemContainer container = containerBlock.getItemContainer();
         if (container == null) {
            return;
         }

         StandRuntime.applySlotFilters(container);
      } catch (Exception e) {
         LOGGER.atWarning().withCause(e).log("Could not apply armor slot filters on add");
      }
   }

   public void onEntityRemove(
      @Nonnull Ref<ChunkStore> ref, @Nonnull RemoveReason reason, @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer
   ) {
      ArmorStandComponent stand = commandBuffer.getComponent(ref, this.armorStandComponentType);
      if (stand != null) {
         BlockStateInfo blockInfo = commandBuffer.getComponent(ref, this.blockStateInfoComponentType);
         if (blockInfo != null) {
            Ref<ChunkStore> sectionRef = blockInfo.getSectionRef();
            if (sectionRef != null && sectionRef.isValid()) {
               Vector3i position = new Vector3i();
               if (blockInfo.fillWorldPos(commandBuffer, position)) {
                  int x = position.x;
                  int y = position.y;
                  int z = position.z;
                  World world = commandBuffer.getExternalData().getWorld();
                  if (reason == RemoveReason.UNLOAD) {
                     // The chunk is going away and takes the figure with it, so there is nothing to
                     // despawn. The entry still has to go, or it outlives the block it describes.
                     StandRuntime.forgetBlock(x, y, z, world);
                  } else {
                     StandRuntime.cleanupBlock(x, y, z, world);
                     LOGGER.atInfo().log("ArmorStand block removed at " + x + "," + y + "," + z + ", mannequin cleaned up");
                  }
               }
            }
         }
      }
   }

   @Nullable
   public Query<ChunkStore> getQuery() {
      return this.query;
   }
}
