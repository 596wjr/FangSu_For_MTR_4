package com.fangsu.train;

import com.fangsu.mtr.DrawableRoute;
import com.fangsu.mtr.LocalRoute;
import com.fangsu.render.sowcer.math.Matrix4f;
import com.fangsu.render.sowcer.math.Vector3f;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.NTETrainWrapper;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleWrapper;
import org.mtr.core.data.*;
import org.mtr.mod.client.MinecraftClientData;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 {@link NTETrainWrapper}（而非 MTR3 TrainClient）的列车状态包装。
 * <p>
 * 站台/路线信息通过 {@link VehicleWrapper.Stop} 获取，
 * 兼容 MTR4 的 VehicleExtension 数据模型。
 */
public class TrainStatus {
    private final NTETrainWrapper train;

    public final boolean[] doorLeftOpen;
    public final boolean[] doorRightOpen;

    public final Vector3f[] lastCarPosition;
    public final Vector3f[] lastCarRotation;
    public final Matrix4f[] lastWorldPose;

    public boolean shouldRender;
    public boolean isInDetailDistance;

    public LocalRoute currentRoute;
    public DrawableRoute drawableRoute;
    public boolean isOnRoute;
    public boolean isReverse;
    /**
     * 0 = no route
     * 1 = waiting
     * 2 = leaving
     * 3 = on_route
     * 4 = arrived
     * 5 = changing
     * 6 = returning
     */
    public int trainStatus;

    public TrainStatus(NTETrainWrapper train) {
        this.train = train;
        final int trainCars = train.getCarCount();
        doorLeftOpen = new boolean[trainCars];
        doorRightOpen = new boolean[trainCars];
        lastWorldPose = new Matrix4f[trainCars];
        lastCarPosition = new Vector3f[trainCars];
        lastCarRotation = new Vector3f[trainCars];
        shouldRender = true;
        isInDetailDistance = false;

        // 从 NTETrainWrapper 复制车厢位置
        for (int i = 0; i < trainCars; i++) {
            lastCarPosition[i] = new Vector3f(
                    (float) train.lastCarPosition[i].x(),
                    (float) train.lastCarPosition[i].y(),
                    (float) train.lastCarPosition[i].z()
            );
            lastCarRotation[i] = new Vector3f(
                    (float) train.lastCarRotation[i].x(),
                    (float) train.lastCarRotation[i].y(),
                    (float) train.lastCarRotation[i].z()
            );
        }
    }

    public void updateRoute() {
        final List<VehicleWrapper.Stop> allPlatforms = train.getAllPlatforms();
        final int nextIndex = train.getAllPlatformsNextIndex();

        if (nextIndex < allPlatforms.size()) {
            final VehicleWrapper.Stop nextStop = allPlatforms.get(nextIndex);
            this.currentRoute = stopToLocalRoute(nextStop);
        } else {
            final long routeId = train.getMtrVehicle().vehicleExtraData.getThisRouteId();
            if (routeId != 0) {
                final Route route = MinecraftClientData.getInstance().routeIdMap.get(routeId);
                this.currentRoute = route != null ? new LocalRoute(route) : null;
            } else {
                this.currentRoute = null;
            }
        }

        if (currentRoute != null) {
            this.drawableRoute = DrawableRoute.requestLongestRoute(currentRoute);
        } else {
            this.drawableRoute = null;
        }

        this.isOnRoute = train.getMtrVehicle().getIsOnRoute();
        this.isReverse = train.getMtrVehicle().getReversed();
        this.trainStatus = calcTrainStatus();
    }

    public void update(int carIndex, boolean doorLeftOpen, boolean doorRightOpen, Matrix4f carPose) {
        updateRoute();
        this.doorLeftOpen[carIndex] = doorLeftOpen;
        this.doorRightOpen[carIndex] = doorRightOpen;
        this.lastWorldPose[carIndex] = carPose.copy();
        this.lastCarPosition[carIndex] = carPose.getTranslationPart();
    }

    /**
     * 获取完整停靠站列表（所有路线合并）。
     */
    public List<VehicleWrapper.Stop> getAllPlatforms() {
        return train.getAllPlatforms();
    }

    /**
     * 获取当前路线的停靠站列表。
     */
    public List<VehicleWrapper.Stop> getThisRoutePlatforms() {
        return train.getThisRoutePlatforms();
    }

    /**
     * 获取当前路线下一站索引（本地索引）。
     */
    public int getThisRoutePlatformsNextIndex() {
        return train.getNextStopIndex(train.getThisRouteStops(), 0);
    }

    /**
     * 获取全局下一站索引（用于 DrawableRoute）。
     */
    public int getThisRoutePlatformsNextIndexGlobal() {
        final int localIndex = getThisRoutePlatformsNextIndex();
        if (drawableRoute == null) return localIndex;
        return drawableRoute.beginIndexInclusive + localIndex;
    }

    /**
     * 获取完整列表的下一站索引。
     */
    public int getAllPlatformsNextIndex() {
        return train.getAllPlatformsNextIndex();
    }

    private int calcTrainStatus() {
        if (currentRoute == null) return 0;
        if (!train.getMtrVehicle().getIsOnRoute()) {
            // 有路线但不在正线上：有速度→出库中，无速度→等待中
            return train.getMtrVehicle().getSpeed() > 0.01 ? 2 : 1;
        }
        final int nextIndex = getAllPlatformsNextIndex();
        final int platformCount = train.getAllPlatforms().size();
        if (platformCount == 0) {
            return 3;
        }
        if (nextIndex >= platformCount) return 6;
        if (onPlatformRail()) return 4;
        return 3;
    }

    private boolean onPlatformRail() {
        final int nextIndex = getAllPlatformsNextIndex();
        final List<VehicleWrapper.Stop> allPlatforms = train.getAllPlatforms();
        if (nextIndex >= allPlatforms.size()) return false;

        final List<PathData> path = train.getPathData();
        if (path.isEmpty()) return false;

        final long nextPlatformId = allPlatforms.get(nextIndex).platform != null
                ? allPlatforms.get(nextIndex).platform.getId() : 0;

        // 检查车头或车尾是否在目标站台轨道上
        final int idx1 = Math.max(0, Math.min(getPathIndex(getRailProgress(0), false), path.size() - 1));
        final int idx2 = Math.max(0, Math.min(getPathIndex(getRailProgress(getCarCount() - 1), true), path.size() - 1));
        final PathData path1 = path.get(idx1);
        final PathData path2 = path.get(idx2);

        return (path1.getDwellTime() != 0 && path1.getSavedRailBaseId() == nextPlatformId) ||
                (path2.getDwellTime() != 0 && path2.getSavedRailBaseId() == nextPlatformId);
    }

    /**
     * 将 {@link VehicleWrapper.Stop} 转换为方速用的 {@link LocalRoute}。
     */
    private LocalRoute stopToLocalRoute(VehicleWrapper.Stop stop) {
        if (stop.route == null) return null;
        final Route route = MinecraftClientData.getInstance().routeIdMap.get(stop.route.getId());
        return route != null ? new LocalRoute(route) : null;
    }

    // ========== 向后兼容的便捷方法 ==========

    @SuppressWarnings("unused")
    public NTETrainWrapper getWrapper() {
        return train;
    }

    @SuppressWarnings("unused")
    public long id() {
        return train.getId();
    }

    @SuppressWarnings("unused")
    public Siding siding() {
        return train.getSiding();
    }

    @SuppressWarnings("unused")
    public String trainTypeId() {
        return train.getVehicleId(0);
    }

    @SuppressWarnings("unused")
    public TransportMode transportMode() {
        return train.getTransportMode();
    }

    @SuppressWarnings("unused")
    public int getCarCount() {
        return train.getCarCount();
    }

    @SuppressWarnings("unused")
    public int trainCars() {
        return train.getCarCount();
    }

    @SuppressWarnings("unused")
    public float accelerationConstant() {
        return (float) (train.getServiceAcceleration() * 1000 * 1000 / (1 / 400.0));
    }

    @SuppressWarnings("unused")
    public boolean manualAllowed() {
        return train.isManualAllowed();
    }

    @SuppressWarnings("unused")
    public int manualToAutomaticTime() {
        return train.getManualToAutomaticTime();
    }

    @SuppressWarnings("unused")
    public List<PathData> path() {
        return train.getPathData();
    }

    @SuppressWarnings("unused")
    public double railProgress() {
        return train.getRailProgress();
    }

    @SuppressWarnings("unused")
    public double getRailProgress(int car) {
        return train.getRailProgress() - (double) car * (train.getLength(0));
    }

    @SuppressWarnings("unused")
    public int getPathIndex(double railProgress, boolean roundDown) {
        final List<PathData> path = train.getPathData();
        if (path.isEmpty()) return 0;
        final int index = (int) (railProgress / (train.getLength(0)));
        if (roundDown) {
            return Math.max(0, Math.min(index, path.size() - 1));
        } else {
            return Math.max(0, Math.min((int) Math.ceil(railProgress / (train.getLength(0))), path.size() - 1));
        }
    }

    @SuppressWarnings("unused")
    public int spacing() {
        return (int) train.getLength(0);
    }

    @SuppressWarnings("unused")
    public int width() {
        return (int) train.getWidth(0);
    }

    @SuppressWarnings("unused")
    public float speed() {
        return (float) (train.getSpeedMs() * 20);
    }

    @SuppressWarnings("unused")
    public float doorValue() {
        return (float) train.getDoorValue();
    }

    @SuppressWarnings("unused")
    public boolean isCurrentlyManual() {
        return false; // MTR4 中手动驾驶通过不同机制处理
    }

    @SuppressWarnings("unused")
    public boolean isReversed() {
        return train.getMtrVehicle().getReversed();
    }

    @SuppressWarnings("unused")
    public boolean isOnRoute() {
        return train.getMtrVehicle().getIsOnRoute();
    }

    @SuppressWarnings("unused")
    public boolean justOpening() {
        return train.getDoorValue() > 0;
    }

    @SuppressWarnings("unused")
    public boolean justClosing(float doorCloseTime) {
        return train.getDoorValue() > 0;
    }

    @SuppressWarnings("unused")
    public final boolean isDoorOpening() {
        return train.getDoorValue() > 0;
    }

    @SuppressWarnings("unused")
    public boolean doorTarget() {
        return train.isDoorOpening();
    }
}
