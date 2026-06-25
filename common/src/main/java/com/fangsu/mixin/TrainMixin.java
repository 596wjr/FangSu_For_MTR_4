package com.fangsu.mixin;

import com.fangsu.blockEntities.IPlatformDoor;
import com.fangsu.blocks.IBlockPlatform;
import org.joml.Vector3d;
import org.mtr.mapping.holder.*;
import org.mtr.mod.Init;
import org.mtr.mod.render.PositionAndRotation;
import org.mtr.mod.render.RenderVehicleHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RenderVehicleHelper.class, remap = false, priority = 596)
public abstract class TrainMixin {
    @Shadow(remap = false)
    @Final
    private static int CHECK_DOOR_RADIUS_XZ;

    @Shadow(remap = false)
    @Final
    private static int CHECK_DOOR_RADIUS_Y;

    @Inject(
            method = "canOpenDoors",
            at = @At("RETURN"),
            cancellable = true,
            remap = false
    )
    private static void met$InjectPlatformDetection(Box doorway, PositionAndRotation positionAndRotation, double doorValue, CallbackInfoReturnable<Boolean> cir) {
        final ClientWorld clientWorld = MinecraftClient.getInstance().getWorldMapped();
        if (clientWorld == null) {
            return;
        }

        final Vector3d doorwayPosition1 = positionAndRotation.transformForwards(new Vector3d(doorway.getMinXMapped(), doorway.getMaxYMapped(), doorway.getMinZMapped()), Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);
        final Vector3d doorwayPosition2 = positionAndRotation.transformForwards(new Vector3d(doorway.getMaxXMapped(), doorway.getMaxYMapped(), doorway.getMinZMapped()), Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);
        final Vector3d doorwayPosition3 = positionAndRotation.transformForwards(new Vector3d(doorway.getMaxXMapped(), doorway.getMaxYMapped(), doorway.getMaxZMapped()), Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);
        final Vector3d doorwayPosition4 = positionAndRotation.transformForwards(new Vector3d(doorway.getMinXMapped(), doorway.getMaxYMapped(), doorway.getMaxZMapped()), Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);
        final double minX = Math.min(Math.min(doorwayPosition1.x(), doorwayPosition2.x()), Math.min(doorwayPosition3.x(), doorwayPosition4.x()));
        final double maxX = Math.max(Math.max(doorwayPosition1.x(), doorwayPosition2.x()), Math.max(doorwayPosition3.x(), doorwayPosition4.x()));
        final double minY = Math.min(Math.min(doorwayPosition1.y(), doorwayPosition2.y()), Math.min(doorwayPosition3.y(), doorwayPosition4.y()));
        final double maxY = Math.max(Math.max(doorwayPosition1.y(), doorwayPosition2.y()), Math.max(doorwayPosition3.y(), doorwayPosition4.y()));
        final double minZ = Math.min(Math.min(doorwayPosition1.z(), doorwayPosition2.z()), Math.min(doorwayPosition3.z(), doorwayPosition4.z()));
        final double maxZ = Math.max(Math.max(doorwayPosition1.z(), doorwayPosition2.z()), Math.max(doorwayPosition3.z(), doorwayPosition4.z()));
        boolean canOpenDoors = false;


        for (double checkX = minX - CHECK_DOOR_RADIUS_XZ; checkX <= maxX + CHECK_DOOR_RADIUS_XZ; checkX++) {
            for (double checkY = minY - CHECK_DOOR_RADIUS_Y; checkY <= maxY + CHECK_DOOR_RADIUS_Y; checkY++) {
                for (double checkZ = minZ - CHECK_DOOR_RADIUS_XZ; checkZ <= maxZ + CHECK_DOOR_RADIUS_XZ; checkZ++) {
                    final BlockPos checkPos = Init.newBlockPos(checkX, checkY, checkZ);
                    final BlockState blockState = clientWorld.getBlockState(checkPos);
                    final Block block = blockState.getBlock();
                    if (block.data instanceof IBlockPlatform) {
                        canOpenDoors = true;

                        BlockEntity blockEntity = clientWorld.getBlockEntity(checkPos);
                        if(blockEntity != null && blockEntity.data instanceof IPlatformDoor platformDoor){
                            platformDoor.setDoorValue((float) doorValue);
                        }
                    }
                }
            }
        }

//        for (double checkX = minX - CHECK_DOOR_RADIUS_XZ; checkX <= maxX + CHECK_DOOR_RADIUS_XZ; checkX++) {
//            for (double checkY = minY - CHECK_DOOR_RADIUS_Y; checkY <= maxY + CHECK_DOOR_RADIUS_Y; checkY++) {
//                for (double checkZ = minZ - CHECK_DOOR_RADIUS_XZ; checkZ <= maxZ + CHECK_DOOR_RADIUS_XZ; checkZ++) {
//                    final BlockPos checkPos = Init.newBlockPos(checkX, checkY, checkZ);
//                    final BlockState blockState = clientWorld.getBlockState(checkPos);
//                    final Block block = blockState.getBlock();
//                    if (block.data instanceof PlatformHelper) {
//                        canOpenDoors = true;
//                    }
//                }
//            }
//        }

        cir.setReturnValue(canOpenDoors || cir.getReturnValue());
    }
}