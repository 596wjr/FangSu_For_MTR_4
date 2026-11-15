package com.fangsu.train;

import com.fangsu.mtr.DrawableRoute;
import com.fangsu.mtr.LocalRoute;
import com.fangsu.mtr.RouteStopStatus;
import com.fangsu.utils.MtrUtil;
import com.fangsu.render.sowcer.math.Matrix4f;
import com.fangsu.render.sowcer.math.Vector3f;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.NTETrainWrapper;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleWrapper;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleDataCache;
import org.mtr.core.data.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 {@link NTETrainWrapper}（而非 MTR3 TrainClient）的列车状态包装。
 * <p>
 * 站台/路线信息通过 {@link VehicleWrapper.Stop} 获取，
 * 兼容 MTR4 的 VehicleExtension 数据模型。
 */
public class TrainStatus {
    private final VehicleWrapper train;

    public final boolean[] doorLeftOpen;
    public final boolean[] doorRightOpen;

    /**
     * 逐车节的开门方向（实时值）：最近一次「实际观测」到的原始帧，<b>不</b>按反向规范化。
     * <p>
     * 长度 = 车厢数；从未观测到 / 车型无车门数据时整组为 {@code UNKNOWN}。
     * 由 {@link DoorSideObserverMtr4#lastSides(long)} 刷新（见 {@link #updateRoute()}）。
     */
    public volatile DoorOpenRecord.DoorOpenSide[] doorOpenSides;

    /**
     * 逐车节的开门方向（预测值）：按「本车配置」从实际记录或本地停车模拟查出的规范帧，
     * 不含实时观测；原始字段仍是规范帧，通过 getDoorOpenSide(car) 取得换算后的方向。
     * <p>
     * 长度 = 车厢数；没有记录且停车姿态/目标区块未就绪时整组为 {@code UNKNOWN}。
     */
    public volatile DoorOpenRecord.DoorOpenSide[] predictedDoorOpenSides;

    /** 上次预测查询所用的站台 id（0 = 尚未查询）；只有站台变化时才重新查缓存 */
    private long predictedForPlatformId;
    /** 上次预测时缓存的版本号；缓存被写入（含本站刚观测到的记录）时即使站台不变也要重查 */
    private long predictedGeneration = -1L;


    public final Vector3f[] lastCarPosition;
    public final Vector3f[] lastCarRotation;
    public final Matrix4f[] lastWorldPose;

    public boolean shouldRender;
    public boolean isInDetailDistance;

    public LocalRoute currentRoute;
    public DrawableRoute drawableRoute;
    private int nextRoutePassengerIndex = -1;
    private int nextStopIndex = -1;
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

    /** 创建一个空的默认状态（用于 LCD 纹理初始化时避免 null 引用） */
    public TrainStatus() {
        this.train = null;
        doorLeftOpen = new boolean[0];
        doorRightOpen = new boolean[0];
        // 空状态也要给 LCD 一个合法数组，避免脚本读 doorOpenSides[i] 时 NPE
        doorOpenSides = unknownSides(0);
        predictedDoorOpenSides = unknownSides(0);
        predictedForPlatformId = 0;
        predictedGeneration = -1L;
        lastWorldPose = new Matrix4f[0];
        lastCarPosition = new Vector3f[0];
        lastCarRotation = new Vector3f[0];
        shouldRender = true;
        isInDetailDistance = false;
        currentRoute = null;
        drawableRoute = null;
        isOnRoute = false;
        isReverse = false;
        trainStatus = 0;
    }

    public TrainStatus(NTETrainWrapper train) {
        this.train = train;
        final int trainCars = train.getCarCount();
        doorLeftOpen = new boolean[trainCars];
        doorRightOpen = new boolean[trainCars];
        doorOpenSides = unknownSides(trainCars);
        predictedDoorOpenSides = unknownSides(trainCars);
        predictedForPlatformId = 0;
        predictedGeneration = -1L;
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

    /** 用本站的真实门侧展开逐车厢状态；门开着不代表左右两侧同时打开。 */
    public void updateDoorState(double doorValue) {
        for (int car = 0; car < doorLeftOpen.length; car++) {
            final DoorOpenRecord.DoorOpenSide side = car < doorOpenSides.length
                    ? doorOpenSides[car] : DoorOpenRecord.DoorOpenSide.UNKNOWN;
            doorLeftOpen[car] = doorValue > 0.01 && (side == DoorOpenRecord.DoorOpenSide.LEFT || side == DoorOpenRecord.DoorOpenSide.BOTH);
            doorRightOpen[car] = doorValue > 0.01 && (side == DoorOpenRecord.DoorOpenSide.RIGHT || side == DoorOpenRecord.DoorOpenSide.BOTH);
        }
    }

    @SuppressWarnings("unused")
    public int maxManualSpeed() {
        return train == null ? 0 : (int) train.getMaxManualSpeed();
    }

    public void updateRoute() {
        // 默认构造的占位状态（LCD 纹理初始化时用）没有真实列车，门侧相关逻辑一律跳过
        if (train == null) return;
        final VehicleExtraData extra = train.getMtrVehicle().vehicleExtraData;
        final List<VehicleWrapper.Stop> allPlatforms = train.getStops();
        final double progress = train.getRailProgress();
        final PathData physicalStop = JcmStopLookup.nextPhysicalStop(extra, progress);
        nextStopIndex = findNextStopIndex(allPlatforms);
        final VehicleWrapper.Stop next = nextStopIndex < allPlatforms.size() ? allPlatforms.get(nextStopIndex) : null;
        final long physicalId = physicalStop == null ? 0 : physicalStop.getSavedRailBaseId();
        final long reportedId = next == null || next.platform == null ? 0 : next.platform.getId();
        LocalRoute route = next != null && JcmStopLookup.matchesPhysicalStop(next, physicalStop)
                ? stopToLocalRoute(next) : null;
        nextRoutePassengerIndex = route == null ? -1 : orderedRouteIndex(route, allPlatforms, nextStopIndex);

        // 中途上车时，先用客户端收到的实际轨段和 MTR 同步的当前交路，等待 JCM 返回全程停车表。
        final long thisRouteId = extra.getThisRouteId();
        if (nextRoutePassengerIndex < 0 || thisRouteId != 0 && route.id != thisRouteId) {
            final LocalRoute thisRoute = resolveRoute(thisRouteId);
            final LocalRoute nextRoute = resolveRoute(extra.getNextRouteId());
            final long waypoint = physicalId != 0 ? physicalId : extra.getThisPlatformId();
            final int thisIndex = passengerIndexFrom(thisRoute, waypoint);
            final int nextIndex = passengerIndexFrom(nextRoute, physicalId != 0 ? physicalId : extra.getNextPlatformId());
            // 同一个站台可同时是返程终点和本趟起点，以 MTR 同步的行车方向为准。
            if (thisIndex >= 0 && thisIndex < thisRoute.platformIds.size()) {
                route = thisRoute;
                nextRoutePassengerIndex = thisIndex;
            } else if (nextRoutePassengerIndex < 0 && nextIndex >= 0 && nextIndex < nextRoute.platformIds.size()) {
                route = nextRoute;
                nextRoutePassengerIndex = nextIndex;
            } else if (route == null) {
                route = thisRoute == null ? nextRoute : thisRoute;
            }
        }

        final boolean pastLastStop = physicalStop == null && nextRoutePassengerIndex < 0 && train.isStopsDataFetched()
                && !allPlatforms.isEmpty() && allPlatforms.get(0).distance >= 0
                && nextStopIndex >= allPlatforms.size();
        if (pastLastStop) {
            route = stopToLocalRoute(allPlatforms.get(allPlatforms.size() - 1));
            nextRoutePassengerIndex = route == null ? -1 : route.platformIds.size();
        }

        this.currentRoute = route;
        final long nextPlatformId = route != null && nextRoutePassengerIndex >= 0
                && nextRoutePassengerIndex < route.platformIds.size()
                ? route.platformIds.get(nextRoutePassengerIndex).platformId : physicalId;
        final double nextStopDistance = physicalStop != null ? physicalStop.getEndDistance()
                : next != null && next.distance >= 0 && reportedId == nextPlatformId ? next.distance : Double.NaN;

        // ===== 逐车节开门方向（LCD 可直接读，见 DESIGN 第 8 节）=====
        // 实时值：观测端保存的原始帧（未按反向规范化），长度与当前编组一致才采用
        final int carCount = getCarCount();
        final boolean doorsOpen = train.getDoorValue() > 0.01;
        final long plannedPlatformId = DoorSideObserverMtr4.nextPlatformId(train.getMtrVehicle());
        final long doorPlatformId = doorsOpen
                ? DoorSideObserverMtr4.currentPlatformId(train.getMtrVehicle())
                : plannedPlatformId != 0 ? plannedPlatformId : nextPlatformId;
        final DoorOpenRecord.DoorOpenSide[] live = DoorSideObserverMtr4.lastSides(id(), doorPlatformId,
                train.getMtrVehicle().getReversed());
        this.doorOpenSides = doorsOpen && live != null && live.length == carCount ? live : unknownSides(carCount);
        // 预测值：站台变化、或缓存被写入（含本站刚观测到的记录）时重查一次，避免每帧重建数组；
        // 配置键用 车型 + 编组 + 总长（与观测上报的口径完全一致，匹配才准）
        final long cacheGeneration = DoorOpenCacheClient.generation();
        if (doorPlatformId != 0
                && (doorPlatformId != predictedForPlatformId || cacheGeneration != predictedGeneration)) {
            predictedForPlatformId = doorPlatformId;
            predictedGeneration = cacheGeneration;
            final int lengthMeters = (int) Math.round(extra.getTotalVehicleLength());
            final DoorOpenRecord.DoorOpenSide[] predicted = DoorOpenCacheClient.query(
                    doorPlatformId, trainTypeId(), carCount, lengthMeters);
            this.predictedDoorOpenSides = predicted != null && predicted.length == carCount
                    ? predicted
                    : unknownSides(carCount);
        } else if (doorPlatformId == 0) {
            // 站台未知：清掉标记，等下一次拿到站台 id 时重新查
            predictedForPlatformId = 0;
            predictedGeneration = -1L;
            predictedDoorOpenSides = unknownSides(carCount);
        }

        if (currentRoute != null) {
            this.drawableRoute = DrawableRoute.requestLongestRoute(currentRoute);
        } else {
            this.drawableRoute = null;
        }

        this.isOnRoute = train.getMtrVehicle().getIsOnRoute();
        this.isReverse = train.getMtrVehicle().getReversed();
        this.trainStatus = calcTrainStatus(progress, nextStopDistance, pastLastStop);
        updateDoorState(train.getDoorValue());
    }

    /** 当前车厢可用的开门方向（实时优先，预测按 isReverse 换算；未知保持 UNKNOWN）。 */
    public DoorOpenRecord.DoorOpenSide getDoorOpenSide(int carIndex) {
        return DoorSideResolver.resolve(doorOpenSides, predictedDoorOpenSides, carIndex, isReverse, train != null && doorValue() > 0.01);
    }

    public void update(int carIndex, boolean doorLeftOpen, boolean doorRightOpen, Matrix4f carPose) {
        updateRoute();
        this.doorLeftOpen[carIndex] = doorLeftOpen;
        this.doorRightOpen[carIndex] = doorRightOpen;
        this.lastWorldPose[carIndex] = carPose.copy();
        this.lastCarPosition[carIndex] = carPose.getTranslationPart();
    }

    /**
     * 生成一个指定长度、全部为 {@link DoorOpenRecord.DoorOpenSide#UNKNOWN} 的数组。
     * <p>
     * 契约要求 {@code doorOpenSides}/{@code predictedDoorOpenSides} 永远是
     * 「每车节一个」的合法数组（绝不能是 null 或短数组），所以初始化与查不到记录时都用它兜底。
     */
    private static DoorOpenRecord.DoorOpenSide[] unknownSides(int carCount) {
        final DoorOpenRecord.DoorOpenSide[] sides =
                new DoorOpenRecord.DoorOpenSide[Math.max(0, carCount)];
        java.util.Arrays.fill(sides, DoorOpenRecord.DoorOpenSide.UNKNOWN);
        return sides;
    }

    /**
     * 获取完整停靠站列表（所有路线合并）。
     */
    public List<VehicleWrapper.Stop> getAllPlatforms() {
        return train.getStops();
    }

    /**
     * 获取当前路线的停靠站列表。
     */
    public List<VehicleWrapper.Stop> getThisRoutePlatforms() {
        return train.getThisRouteStops();
    }

    /** 下一处实际停靠在线路完整站序中的位置，未知时为 -1。 */
    public int getThisRoutePlatformsNextIndex() {
        return nextRoutePassengerIndex;
    }

    public int getThisRoutePlatformsNextIndexGlobal() {
        return drawableRoute == null ? nextRoutePassengerIndex : drawableRoute.drawingIndex(nextRoutePassengerIndex);
    }

    /** JCM 停车表的下标，可直接用于 getAllPlatforms()。 */
    public int getAllPlatformsNextIndex() {
        return nextStopIndex >= 0 ? nextStopIndex : train == null ? 0 : findNextStopIndex(train.getStops());
    }

    private static int passengerIndexFrom(LocalRoute route, long platformId) {
        if (route == null || platformId == 0) return -1;
        int index = route.getPlatformIdIndex(platformId);
        if (index < 0) return -1;
        while (index < route.platformIds.size() && route.platformIds.get(index).stopStatus != RouteStopStatus.NORMAL) index++;
        return index;
    }

    /** 按出现顺序匹配同一交路的停车表，环线重复经过同一站台时仍用正确的一次。 */
    private static int orderedRouteIndex(LocalRoute route, List<VehicleWrapper.Stop> stops, int target) {
        int start = target;
        while (start > 0 && stops.get(start - 1).route != null && stops.get(start - 1).route.getId() == route.id) {
            start--;
        }
        int matched = -1;
        if (start > 0) {
            final var shared = stops.get(start - 1).roundUpRoute;
            if (shared != null && shared.route != null && shared.route.getId() == route.id && shared.platform != null) {
                matched = findPlatformIndex(route, shared.platform.getId(), 0);
            }
        }
        for (int index = start; index <= target; index++) {
            final var platform = stops.get(index).platform;
            if (platform == null) {
                if (index == target) return -1;
                continue;
            }
            final long id = platform.getId();
            int next = findPlatformIndex(route, id, matched + 1);
            // 同一线路在车厂交路中连续运行时，需要从头匹配下一圈。
            if (next < 0 && matched >= 0) next = findPlatformIndex(route, id, 0);
            if (next < 0 && index == target) return -1;
            if (next >= 0) matched = next;
        }
        return matched;
    }

    private static int findPlatformIndex(LocalRoute route, long id, int start) {
        for (int index = start; index < route.platformIds.size(); index++) {
            final var stop = route.platformIds.get(index);
            if (stop.platformId == id && stop.stopStatus == RouteStopStatus.NORMAL) return index;
        }
        return -1;
    }

    private int findNextStopIndex(List<VehicleWrapper.Stop> stops) {
        if (stops.isEmpty()) return 0;
        if (stops.get(0).distance < 0) {
            final int result = JcmStopLookup.limitedIndex(train.getMtrVehicle().vehicleExtraData, train.getRailProgress(), 0, stops);
            return result < 0 ? stops.size() : result;
        }

        final double progress = train.getRailProgress();
        for (int index = 0; index < stops.size(); index++) {
            final var stop = stops.get(index);
            if (progress > stop.distance + 0.001) continue;
            final LocalRoute route = stopToLocalRoute(stop);
            final int routeIndex = route == null ? -1 : orderedRouteIndex(route, stops, index);
            if (routeIndex >= 0 || route == null || stop.platform == null
                    || route.getPlatformIdIndex(stop.platform.getId()) < 0) return index;
        }
        return stops.size();
    }

    private int calcTrainStatus(double progress, double nextStopDistance, boolean pastLastStop) {
        if (currentRoute == null) return 0;
        if (!isOnRoute) return train.getMtrVehicle().getSpeed() > 0.01 ? 2 : 1;
        if (pastLastStop) return 6;
        if (nextRoutePassengerIndex < 0) return 3;
        if (Double.isFinite(nextStopDistance)) {
            return progress >= nextStopDistance - 3 && progress <= nextStopDistance + 0.001 ? 4 : 3;
        }
        return 3;
    }

    /**
     * 将 {@link VehicleWrapper.Stop} 转换为方速用的 {@link LocalRoute}。
     */
    private LocalRoute stopToLocalRoute(VehicleWrapper.Stop stop) {
        if (stop.route == null) return null;
        // 站序响应中的普通线路也可能只存在于 JCM 的独立数据集，不能回查本地表后丢掉它。
        final var local = resolveRoute(stop.route.getId());
        return local == null ? new LocalRoute(stop.route) : local;
    }

    private static LocalRoute resolveRoute(long routeId) {
        if (routeId == 0) return null;
        // 用完整的简化站序与线路图对齐，远处站台对应的 Platform 实例可能还未加载。
        final SimplifiedRoute synced = MtrUtil.getSimplifiedRouteById(routeId);
        if (synced != null && !synced.getPlatforms().isEmpty()) return new LocalRoute(synced);
        final SimplifiedRoute fetched = VehicleDataCache.mtrData.routeIdMap.get(routeId);
        return fetched != null && !fetched.getPlatforms().isEmpty() ? new LocalRoute(fetched) : MtrUtil.getRouteById(routeId);
    }

    // ========== 向后兼容的便捷方法 ==========

    @SuppressWarnings("unused")
    public VehicleWrapper getWrapper() {
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
