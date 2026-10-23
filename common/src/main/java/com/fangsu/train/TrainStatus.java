package com.fangsu.train;

import com.fangsu.mtr.DrawableRoute;
import com.fangsu.mtr.LocalRoute;
import com.fangsu.utils.MtrUtil;
import com.fangsu.render.sowcer.math.Matrix4f;
import com.fangsu.render.sowcer.math.Vector3f;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.NTETrainWrapper;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleWrapper;
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
    public DoorOpenRecord.DoorOpenSide[] doorOpenSides;

    /**
     * 逐车节的开门方向（预测值）：按「本车配置」从站台缓存查出的规范帧，
     * 已按本车当前反向状态由调用方/查询层给出的原值，<b>不含</b>实时观测。
     * <p>
     * 长度 = 车厢数；该站台没有记录时整组为 {@code UNKNOWN}。
     */
    public DoorOpenRecord.DoorOpenSide[] predictedDoorOpenSides;

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
    /** 下一站的站台 id（0 = 未知），用于在「当前线路自己的站序」里定位索引 */
    private long nextPlatformId;
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
        nextPlatformId = 0;
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

    /**
     * MTR4 无逐车厢门侧数据，用车辆级 doorValue 近似填充两侧（0~1，>0.01 视为开）。
     * 如需精确逐侧状态，可后续从 immutablePath + 站台方位推导。
     */
    public void updateDoorState(double doorValue) {
        final boolean open = doorValue > 0.01;
        java.util.Arrays.fill(doorLeftOpen, open);
        java.util.Arrays.fill(doorRightOpen, open);
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
        final int nextIndex = train.getNextStopIndex(allPlatforms, 0.0);

        // 「下一站」的站台 id（0 = 未知）。停站时它指向正在停靠的那一站，
        // 出站后才指向下一站——即「下一站索引」始终指向屏幕上该高亮的那一站，
        // 这也是到站画面（trainStatus == 4）能取到本站的前提。
        long nextPlatformId = 0;
        LocalRoute nextStopRoute = null;
        if (nextIndex < allPlatforms.size()) {
            final VehicleWrapper.Stop nextStop = allPlatforms.get(nextIndex);
            nextStopRoute = stopToLocalRoute(nextStop);
            if (nextStop.platform != null) {
                nextPlatformId = nextStop.platform.getId();
            }
        }
        // JCM 的停靠表拿不到站台（远端/隐藏线路数据缺失）时，用 MTR 自己同步的下一站站台兜底
        if (nextPlatformId == 0) {
            nextPlatformId = extra.getNextPlatformId();
        }

        // 决定「当前线路」：
        // ① 优先用下一站所属线路（过终点站后下一站就是下一条线路的站，天然完成切换）；
        // ② JCM 停靠点的 route 字段来自另一份数据集，可能为空或滞后，
        //    此时回退到 MTR 同步的 nextRouteId / thisRouteId，否则整块屏会一直画上一条线路，
        //    表现为「过终点站后 index 不归零」。
        LocalRoute route = nextStopRoute;
        if (route == null) {
            long routeId = extra.getNextRouteId();
            if (routeId == 0) routeId = extra.getThisRouteId();
            if (routeId != 0) {
                // MtrUtil 查询链：完整 Route 优先，miss 时回退 SimplifiedRoute（含隐藏线路缓存）
                route = MtrUtil.getRouteById(routeId);
            }
        }
        // ③ 兜底校验：下一站的站台必须能在该线路自己的站序里找到，否则说明线路选错了
        //    （典型情况是刚过终点站、JCM 仍指向旧线路），换一条真正包含这一站的线路，
        //    索引才会按新线路的站序从起点重新算。
        if (nextPlatformId != 0 && route != null && routePlatformIndex(route, nextPlatformId) < 0) {
            route = findRouteContaining(extra, nextPlatformId, route);
        }
        this.currentRoute = route;
        this.nextPlatformId = nextPlatformId;

        // ===== 逐车节开门方向（LCD 可直接读，见 DESIGN 第 8 节）=====
        // 实时值：观测端保存的原始帧（未按反向规范化），长度与当前编组一致才采用
        final int carCount = getCarCount();
        final DoorOpenRecord.DoorOpenSide[] live = DoorSideObserverMtr4.lastSides(id());
        if (live != null && live.length == carCount) {
            this.doorOpenSides = live.clone();
        }
        // 预测值：站台变化、或缓存被写入（含本站刚观测到的记录）时重查一次，避免每帧重建数组；
        // 配置键用 车型 + 编组 + 总长（与观测上报的口径完全一致，匹配才准）
        final long cacheGeneration = DoorOpenCacheClient.generation();
        if (nextPlatformId != 0
                && (nextPlatformId != predictedForPlatformId || cacheGeneration != predictedGeneration)) {
            predictedForPlatformId = nextPlatformId;
            predictedGeneration = cacheGeneration;
            final int lengthMeters = (int) Math.round(extra.getTotalVehicleLength());
            final DoorOpenRecord.DoorOpenSide[] predicted = DoorOpenCacheClient.query(
                    nextPlatformId, trainTypeId(), carCount, lengthMeters);
            this.predictedDoorOpenSides = predicted != null && predicted.length == carCount
                    ? predicted
                    : unknownSides(carCount);
        } else if (nextPlatformId == 0) {
            // 站台未知：清掉标记，等下一次拿到站台 id 时重新查
            predictedForPlatformId = 0;
            predictedGeneration = -1L;
            if (predictedDoorOpenSides.length != carCount) {
                predictedDoorOpenSides = unknownSides(carCount);
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

    /**
     * 获取当前路线下一站索引（本线路内的索引，0 = 本线路第一个站）。
     * <p>
     * 优先用下一站的站台 id 到<b>当前线路自己的站序</b>里定位：
     * <ul>
     *   <li>过终点站进入下一条线路时，索引天然从新线路的起点重新算，
     *       不会沿用上一条线路的进度（这就是「index 不归零」的根因）；</li>
     *   <li>{@code Stop.distance} 是整条运行路径的累计里程，而本线路的停靠表里
     *       还包含「与上一条线路共用的首站」这种重复项，按里程推进很容易多推或少推一站。</li>
     * </ul>
     * 站台 id 拿不到、或者该站台不在这条线路的站序里时，才退回按里程推进。
     */
    public int getThisRoutePlatformsNextIndex() {
        if (currentRoute != null && nextPlatformId != 0) {
            final int index = routePlatformIndex(currentRoute, nextPlatformId);
            if (index >= 0) return index;
        }
        return findNextStopIndex(train.getThisRouteStops());
    }

    /**
     * 站台在指定线路站序里的索引（-1 = 不在这条线路上）。
     * <p>
     * 先用 {@link SimplifiedRoute}（客户端数据始终完整、且与 MTR 的服务端站序一致），
     * 查不到再退回到本地 {@link LocalRoute#getPlatformIdIndex(long)}。
     */
    private static int routePlatformIndex(LocalRoute route, long platformId) {
        final SimplifiedRoute simplifiedRoute = MtrUtil.getSimplifiedRouteById(route.id);
        if (simplifiedRoute != null) {
            final int index = simplifiedRoute.getPlatformIndex(platformId);
            if (index >= 0) return index;
        }
        return route.getPlatformIdIndex(platformId);
    }

    /**
     * 在 MTR 同步的候选线路里找出真正包含 {@code platformId} 的那一条。
     * 只有当前线路定位不到该站时才会走到这里（见 {@link #updateRoute()}）。
     */
    private static LocalRoute findRouteContaining(VehicleExtraData extra, long platformId, LocalRoute fallback) {
        final long[] candidateIds = {extra.getNextRouteId(), extra.getThisRouteId()};
        for (long routeId : candidateIds) {
            if (routeId == 0 || (fallback != null && routeId == fallback.id)) continue;
            final LocalRoute candidate = MtrUtil.getRouteById(routeId);
            if (candidate != null && routePlatformIndex(candidate, platformId) >= 0) {
                return candidate;
            }
        }
        return fallback;
    }

    /**
     * 获取全局下一站索引（用于 DrawableRoute）。
     * <p>
     * {@code beginIndexInclusive} 是「当前线路第一站」在长交路整图中的位置，
     * 与本地索引相加即得下一站在整图中的位置；本地索引来自线路自身站序，
     * 因此换线后不会把上一条线路的站数算进来。
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
        return findNextStopIndex(train.getStops());
    }

    /**
     * 求「下一站」索引。
     * <p>
     * <b>不能直接用 {@code VehicleWrapper.getNextStopIndex}：</b>它优先用停靠点的
     * {@code distance}（{@code DataFetchMode.SKIP} 下 JCM 的 limited stops data
     * 里全是 {@code -1}），拿不到就退回比较
     * {@code vehicleExtraData.getThisPlatformId() == stop.platform.getId()}——
     * 而这两个 id 不是同一套（前者是车辆记录里的站台 id，后者来自
     * {@code SimplifiedRoutePlatform}），实测永不相等，于是它返回 {@code stops.size()}，
     * 导致 {@code updateRoute()} 落到「用 thisRouteId 查线路」的分支，
     * 表现出「下一站永远是第一站」「到终点后无线路信息」。
     * <p>
     * <b>前提是停靠点的 {@code distance} 必须可用。</b>{@code DataFetchMode.SKIP} 下
     * JCM 的 limited stops data 会把 {@code distance} 全写成 {@code -1}，
     * 那时 {@code getNextStopIndex} 会退回比较
     * {@code vehicleExtraData.getThisPlatformId() == stop.platform.getId()}
     * （两套 id 不同源，匹配不上）并返回 {@code stops.size()}，
     * 表现为「下一站永远是第一站」「到终点后无线路信息」。
     * 所以 {@link VehicleLcdRenderer#buildStatus} 用
     * {@code DataFetchMode.MANDATORY} 强制向服务端取完整站序（带真实里程）。
     */
    private int findNextStopIndex(List<VehicleWrapper.Stop> stops) {
        if (stops == null || stops.isEmpty()) return 0;

        // 里程可用：按里程推进
        if (stops.get(0).distance >= 0) {
            final double progress = train.getRailProgress();
            int idx = 0;
            for (VehicleWrapper.Stop stop : stops) {
                if (progress > stop.distance) idx++;
                else break;
            }
            return Math.min(idx, stops.size());
        }

        // 兜底：服务端数据还没到（首帧）时用 JCM 原实现
        return train.getNextStopIndex(stops, 0);
    }

    private int calcTrainStatus() {
        if (currentRoute == null) return 0;
        if (!train.getMtrVehicle().getIsOnRoute()) {
            // 有路线但不在正线上：有速度→出库中，无速度→等待中
            return train.getMtrVehicle().getSpeed() > 0.01 ? 2 : 1;
        }
        final int nextIndex = getAllPlatformsNextIndex();
        final int platformCount = train.getStops().size();
        if (platformCount == 0) {
            return 3;
        }
        if (nextIndex >= platformCount) return 6;
        if (isArrived()) return 4;
        return 3;
    }

    /** 到站判定窗口（米）：车头进入「距下一站 {@code ARRIVING_DISTANCE} 以内」就算到站 */
    private static final double ARRIVING_DISTANCE = 3.0;

    /**
     * 列车是否已到站。
     * <p>
     * 判据：下一站有真实里程，且
     * {@code nextStop.distance - ARRIVING_DISTANCE <= railProgress <= nextStop.distance}。
     * 即车头已经进入该站台前的判定窗口、且还未冲过站台（后者说明是经过而非停靠）。
     * <p>
     * 用里程而不是站台 id：MTR 的 {@code thisPlatformId} / path 的
     * {@code savedRailBaseId} 与 {@code Stop.platform.getId()} 不同源，
     * 比起来很容易永远不相等，而 {@code distance} 一旦随站序数据到手就是准的。
     */
    private boolean isArrived() {
        final List<VehicleWrapper.Stop> stops = train.getStops();
        final int nextIndex = getAllPlatformsNextIndex();
        if (stops.isEmpty() || nextIndex >= stops.size()) return false;

        final VehicleWrapper.Stop nextStop = stops.get(nextIndex);
        if (nextStop.distance < 0) return false; // 站序数据未就绪

        final double progress = train.getRailProgress(0);
        return progress >= nextStop.distance - ARRIVING_DISTANCE && progress <= nextStop.distance;
    }

    /**
     * 将 {@link VehicleWrapper.Stop} 转换为方速用的 {@link LocalRoute}。
     */
    private LocalRoute stopToLocalRoute(VehicleWrapper.Stop stop) {
        if (stop.route == null) return null;
        // MtrUtil 查询链：主通道 miss（隐藏线路）时回退 SimplifiedRoute
        return MtrUtil.getRouteById(stop.route.getId());
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
