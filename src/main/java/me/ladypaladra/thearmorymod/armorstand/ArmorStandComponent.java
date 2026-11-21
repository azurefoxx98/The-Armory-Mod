package me.ladypaladra.thearmorymod.armorstand;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.UUID;

public class ArmorStandComponent implements Component<ChunkStore> {
   private static ComponentType<ChunkStore, ArmorStandComponent> COMPONENT_TYPE;
   public static final BuilderCodec<ArmorStandComponent> CODEC = BuilderCodec.builder(ArmorStandComponent.class, ArmorStandComponent::new)
      .append(new KeyedCodec<>("MannequinUUID", Codec.UUID_STRING),
         (stand, uuid) -> stand.mannequinUuid = uuid,
         stand -> stand.mannequinUuid)
      .add()
      .append(new KeyedCodec<>("StaticPose", Codec.NULLABLE_BOOLEAN),
         (stand, staticPose) -> stand.staticPose = Boolean.TRUE.equals(staticPose),
         stand -> stand.staticPose)
      .add()
      .build();
   private UUID mannequinUuid = null;
   private boolean staticPose = false;

   public ArmorStandComponent() {
   }

   public ArmorStandComponent(ArmorStandComponent other) {
      this.mannequinUuid = other.mannequinUuid;
      this.staticPose = other.staticPose;
   }

   public UUID getMannequinUuid() {
      return this.mannequinUuid;
   }

   public void setMannequinUuid(UUID mannequinUuid) {
      this.mannequinUuid = mannequinUuid;
   }

   public boolean isStaticPose() {
      return this.staticPose;
   }

   public void setStaticPose(boolean staticPose) {
      this.staticPose = staticPose;
   }

   public static ComponentType<ChunkStore, ArmorStandComponent> getComponentType() {
      return COMPONENT_TYPE;
   }

   public static void setComponentType(ComponentType<ChunkStore, ArmorStandComponent> type) {
      COMPONENT_TYPE = type;
   }

   public Component<ChunkStore> clone() {
      return new ArmorStandComponent(this);
   }
}
