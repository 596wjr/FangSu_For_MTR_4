package com.fangsu.train;

import com.fangsu.blockEntities.IPlatformDoor;
import com.fangsu.blocks.IBlockPlatform;
import com.fangsu.mixin.VehicleDoorPredictionAccessor;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Vehicle;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.Box;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Property;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockPSDAPGDoorBase;
import org.mtr.mod.block.PlatformHelper;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.PositionAndRotation;

/** 下一站停车姿态预测。只读模型、轨道和已加载的世界方块，不触发列车/屏蔽门模拟。 */
final class DoorSidePredictorMtr4 {
    private static final Class<?> METROPOLIS_PLATFORM = optionalClass("team.dovecotmc.metropolis.block.interfaces.IBlockPlatform");

    private DoorSidePredictorMtr4() {
    }

    static PathData nextStop(VehicleExtension vehicle) {
        final double progress = ((VehicleDoorPredictionAccessor) vehicle).fangsu$getRailProgress();
        for (final PathData path : vehicle.vehicleExtraData.immutablePath) {
            if (path.getEndDistance() + 0.001 < progress || path.getDwellTime() <= 0) continue;
            if (path.getSavedRailBaseId() == 0 || path.getSavedRailBaseId() == vehicle.vehicleExtraData.getSidingId()) continue;
            return path;
        }
        return null;
    }

    static void predict(VehicleExtension vehicle) {
        if (!vehicle.getIsOnRoute() || vehicle.persistentVehicleData.getDoorValue() > 0.01) return;
        final PathData stop = nextStop(vehicle);
        if (stop == null || vehicle.vehicleExtraData.immutableVehicleCars.isEmpty()) return;
        final long platform = stop.getSavedRailBaseId();
        final String type = vehicle.vehicleExtraData.immutableVehicleCars.get(0).getVehicleId();
        final int cars = vehicle.vehicleExtraData.immutableVehicleCars.size();
        final int length = (int) Math.round(vehicle.vehicleExtraData.getTotalVehicleLength());
        if (!DoorOpenCacheClient.hasUnknown(DoorOpenCacheClient.query(platform, type, cars, length))) return;

        // 用独立 Vehicle 复用 MTR 的转向架/车体解算；不临时改写真实车辆，也不创建 PersistentVehicleData。
        final Vehicle parked = new ParkedVehicle(vehicle, stop.getEndDistance());
        final DoorOpenRecord.DoorOpenSide[] sides = DoorSideObserverMtr4.observeDoorSides(parked, true);
        if (sides == null) return; // 目标区块或车型资源未就绪，保留 UNKNOWN，下一轮重试。
        DoorOpenCacheClient.applyPrediction(platform,
                new DoorOpenRecord(sides, type, cars, length, vehicle.getReversed(), 0).normalized());
    }

    /** 与 MTR 的 doorway 扫描范围一致，但没有 canOpenDoors 的开屏蔽门副作用；null 表示区块未就绪。 */
    static Boolean canOpenDoors(Box doorway, PositionAndRotation pose) {
        final ClientWorld world = MinecraftClient.getInstance().getWorldMapped();
        final var level = Minecraft.getInstance().level;
        if (world == null || level == null) return null;
        final Vector3d[] corners = {
                new Vector3d(doorway.getMinXMapped(), doorway.getMaxYMapped(), doorway.getMinZMapped()),
                new Vector3d(doorway.getMaxXMapped(), doorway.getMaxYMapped(), doorway.getMinZMapped()),
                new Vector3d(doorway.getMaxXMapped(), doorway.getMaxYMapped(), doorway.getMaxZMapped()),
                new Vector3d(doorway.getMinXMapped(), doorway.getMaxYMapped(), doorway.getMaxZMapped())
        };
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (final Vector3d corner : corners) {
            final Vector3d point = pose.transformForwards(corner, Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);
            minX = Math.min(minX, point.x()); maxX = Math.max(maxX, point.x());
            minY = Math.min(minY, point.y()); maxY = Math.max(maxY, point.y());
            minZ = Math.min(minZ, point.z()); maxZ = Math.max(maxZ, point.z());
        }
        boolean found = false;
        for (double x = minX - 1; x <= maxX + 1; x++) {
            for (double y = minY - 2; y <= maxY + 2; y++) {
                for (double z = minZ - 1; z <= maxZ + 1; z++) {
                    final BlockPos pos = Init.newBlockPos(x, y, z);
                    if (!level.hasChunkAt(pos.data)) return null;
                    final BlockState state = world.getBlockState(pos);
                    final Object block = state.getBlock().data;
                    final var entity = world.getBlockEntity(pos);
                    // 方速屏蔽门按方块实体锁定状态判断；原版 PSD/APG 按 UNLOCKED 判断。
                    if (entity != null && entity.data instanceof IPlatformDoor door && door.isLocked()) continue;
                    if (block instanceof BlockPSDAPGDoorBase) {
                        if (state.get(new Property<>(BlockPSDAPGDoorBase.UNLOCKED.data))) found = true;
                    } else if (block instanceof PlatformHelper || block instanceof IBlockPlatform
                            || METROPOLIS_PLATFORM.isInstance(block)) {
                        found = true;
                    }
                }
            }
        }
        return found;
    }

    private static Class<?> optionalClass(String name) {
        try {
            return Class.forName(name, false, DoorSidePredictorMtr4.class.getClassLoader());
        } catch (ClassNotFoundException ignored) {
            return Void.class;
        }
    }

    private static final class ParkedVehicle extends Vehicle {
        ParkedVehicle(VehicleExtension source, double stoppingDistance) {
            super(source.vehicleExtraData, null, source.getTransportMode(), MinecraftClientData.getInstance());
            railProgress = stoppingDistance;
            reversed = source.getReversed();
        }
    }
}
