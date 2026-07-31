package com.fangsu.mixin;

import com.fangsu.blockEntities.IPlatformDoor;
import com.fangsu.blocks.IBlockPlatform;
import org.joml.Vector3d;
import org.mtr.mapping.holder.*;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockPSDAPGDoorBase;
import org.mtr.mod.block.PlatformHelper;
import org.mtr.mod.render.PositionAndRotation;
import org.mtr.mod.render.RenderVehicleHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@Mixin(value = RenderVehicleHelper.class, remap = false, priority = 596)
public abstract class TrainMixin {

    @Shadow(remap = false)
    @Final
    private static int CHECK_DOOR_RADIUS_XZ;

    @Shadow(remap = false)
    @Final
    private static int CHECK_DOOR_RADIUS_Y;

    // 用于反射调用 Metropolis 的缓存
    private static boolean metropolisLoaded = false;
    private static Class<?> metropolisPlatformClass;
    private static Class<?> metropolisDoorClass;
    private static Method setOpenStateMethod;

    static {
        try {
            metropolisPlatformClass = Class.forName("team.dovecotmc.metropolis.block.interfaces.IBlockPlatform");
            metropolisDoorClass = Class.forName("team.dovecotmc.old.metropolis.block.interfaces.IBlockMTRPlatformDoor");
            setOpenStateMethod = metropolisDoorClass.getMethod("setOpenState",
                    boolean.class, float.class,
                    Class.forName("net.minecraft.world.level.Level"),
                    Class.forName("net.minecraft.core.BlockPos"),
                    Class.forName("net.minecraft.world.level.block.state.BlockState")
            );
            metropolisLoaded = true;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            metropolisLoaded = false;
        }
    }

    /**
     * 使用 @Overwrite 完全替换 canOpenDoors 方法，避免与原版或 Metropolis 的 @Inject 冲突。
     * 本方法包含了原版逻辑 + 方速门逻辑 + Metropolis 门逻辑（反射）。
     * @author fangsu
     * @reason mixin冲突, 用更激进的方式实现并用反射进行兼容
     */
    @Overwrite(remap = false)
    public static boolean canOpenDoors(Box doorway, PositionAndRotation positionAndRotation, double doorValue) {
        final ClientWorld clientWorld = MinecraftClient.getInstance().getWorldMapped();
        if (clientWorld == null) {
            return false;
        }

        final Vector3d doorwayPosition1 = positionAndRotation.transformForwards(
                new Vector3d(doorway.getMinXMapped(), doorway.getMaxYMapped(), doorway.getMinZMapped()),
                Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);
        final Vector3d doorwayPosition2 = positionAndRotation.transformForwards(
                new Vector3d(doorway.getMaxXMapped(), doorway.getMaxYMapped(), doorway.getMinZMapped()),
                Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);
        final Vector3d doorwayPosition3 = positionAndRotation.transformForwards(
                new Vector3d(doorway.getMaxXMapped(), doorway.getMaxYMapped(), doorway.getMaxZMapped()),
                Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);
        final Vector3d doorwayPosition4 = positionAndRotation.transformForwards(
                new Vector3d(doorway.getMinXMapped(), doorway.getMaxYMapped(), doorway.getMaxZMapped()),
                Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);

        final double minX = Math.min(Math.min(doorwayPosition1.x(), doorwayPosition2.x()),
                Math.min(doorwayPosition3.x(), doorwayPosition4.x()));
        final double maxX = Math.max(Math.max(doorwayPosition1.x(), doorwayPosition2.x()),
                Math.max(doorwayPosition3.x(), doorwayPosition4.x()));
        final double minY = Math.min(Math.min(doorwayPosition1.y(), doorwayPosition2.y()),
                Math.min(doorwayPosition3.y(), doorwayPosition4.y()));
        final double maxY = Math.max(Math.max(doorwayPosition1.y(), doorwayPosition2.y()),
                Math.max(doorwayPosition3.y(), doorwayPosition4.y()));
        final double minZ = Math.min(Math.min(doorwayPosition1.z(), doorwayPosition2.z()),
                Math.min(doorwayPosition3.z(), doorwayPosition4.z()));
        final double maxZ = Math.max(Math.max(doorwayPosition1.z(), doorwayPosition2.z()),
                Math.max(doorwayPosition3.z(), doorwayPosition4.z()));

        boolean canOpenDoors = false;

        for (double checkX = minX - CHECK_DOOR_RADIUS_XZ; checkX <= maxX + CHECK_DOOR_RADIUS_XZ; checkX++) {
            for (double checkY = minY - CHECK_DOOR_RADIUS_Y; checkY <= maxY + CHECK_DOOR_RADIUS_Y; checkY++) {
                for (double checkZ = minZ - CHECK_DOOR_RADIUS_XZ; checkZ <= maxZ + CHECK_DOOR_RADIUS_XZ; checkZ++) {
                    final BlockPos checkPos = Init.newBlockPos(checkX, checkY, checkZ);
                    final BlockState blockState = clientWorld.getBlockState(checkPos);
                    final Block block = blockState.getBlock();
                    final Object blockData = block.data;

                    // ----- 原版 MTR 逻辑：检测 PlatformHelper 和 PSD/APG 门 -----
                    if (blockData instanceof PlatformHelper) {
                        canOpenDoors = true;
                    } else if (blockData instanceof BlockPSDAPGDoorBase && blockState.get(new Property<>(BlockPSDAPGDoorBase.UNLOCKED.data))) {
                        canOpenDoors = true;
                        final BlockEntity blockEntity = clientWorld.getBlockEntity(checkPos);
                        if (blockEntity != null && blockEntity.data instanceof BlockPSDAPGDoorBase.BlockEntityBase) {
                            ((BlockPSDAPGDoorBase.BlockEntityBase) blockEntity.data).setDoorValue(doorValue);
                        }
                    }

                    // ----- 方速自家的门逻辑 -----
                    if (blockData instanceof IBlockPlatform) {
                        canOpenDoors = true;
                        final BlockEntity blockEntity = clientWorld.getBlockEntity(checkPos);
                        if (blockEntity != null && blockEntity.data instanceof IPlatformDoor platformDoor) {
                            platformDoor.setDoorValue((float) doorValue);
                        }
                    }

                    // ----- 兼容 Metropolis 的门逻辑（反射） -----
                    if (metropolisLoaded) {
                        try {
                            if (metropolisPlatformClass.isInstance(blockData) &&
                                    metropolisDoorClass.isInstance(blockData)) {
                                setOpenStateMethod.invoke(blockData,
                                        false,
                                        (float) doorValue,
                                        clientWorld.data,
                                        checkPos.data,
                                        blockState.data
                                );
                                canOpenDoors = true;
                            }
                        } catch (IllegalAccessException | InvocationTargetException ignored) {
                        }
                    }
                }
            }
        }

        return canOpenDoors;
    }
}