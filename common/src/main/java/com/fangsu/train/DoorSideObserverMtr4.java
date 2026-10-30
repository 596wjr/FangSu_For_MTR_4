package com.fangsu.train;

import com.fangsu.Main;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.data.Vehicle;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.holder.Box;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.PositionAndRotation;
import org.mtr.mod.render.RenderVehicleHelper;
import org.mtr.mod.resource.VehicleResourceCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MTR4（4.0.x）进站预测与到站开门方向观测端。
 * <p>
 * 职责：前方停站方向 UNKNOWN 时先只读模拟停车姿态；在「门正在开」时，
 * 用 MTR 自己的渲染数据（{@code getVehicleCarsAndPositions()} + {@code PositionAndRotation}）
 * 和车门判定（{@link RenderVehicleHelper#canOpenDoors}）算出<b>逐车节</b>的开门方向，
 * 规范化后先本地乐观写入（{@link DoorOpenCacheClient#applyUpdate}），
 * 再上报服务端（{@link DoorOpenCacheNet#sendObserveC2S}）。
 * <p>
 * <b>为什么不自己猜站台方位：</b>MTR 渲染每帧本来就在对每个 doorway 调
 * {@code canOpenDoors} 判断「门口有没有站台方块 / 已解锁的屏蔽门」，
 * 这里只是在开门瞬间复用它一次，几何判定与玩家看到的现象完全一致；
 * 副作用（顺带驱动 PSD 屏蔽门）也是 MTR 每帧都在做的事，可以接受。
 * <p>
 * <b>为什么不缓存 PnR：</b>车门判定需要「本帧」的车体姿态，缓存值在弯道上会偏移，
 * 而且这里只在一小段开门窗口里调用，开销可以忽略。
 * <p>
 * 数据存放：{@link #LAST_SIDES} 保存<b>原始帧</b>（未规范化）的观测结果供 LCD 实时读取
 * （见 {@code TrainStatus.doorOpenSides}）；上报给缓存层的是 {@code normalized()} 之后的规范帧。
 */
@Environment(EnvType.CLIENT)
public final class DoorSideObserverMtr4 {

    /** 门开度 ≤ 该值视为「已关门」，复位本回合状态 */
    private static final double DOOR_CLOSED_THRESHOLD = 0.05;
    /** 门开始打开后就用当前位置校正，不再等门开到一半。 */
    private static final double DOOR_OPEN_THRESHOLD = 0.05;
    /** 资源未就绪时的最大重试次数（tick），约 2 秒 */
    private static final int MAX_RETRIES = 40;
    /** 传给 {@code canOpenDoors} 的门开度：给足 2.0，让 PSD 完全打开（与渲染一致） */
    private static final double DOOR_CHECK_VALUE = 2.0;

    /** 每辆车一个观测回合状态 */
    private static final class Episode {
        /** 上一次看到的门开度（仅用于状态对照与调试） */
        double lastDoorValue;
        long platformId;
        boolean reversed;
        /** 本回合是否已判定/上报（避免同一回合反复计算） */
        boolean reported;
        /** 资源未就绪的重试次数 */
        int retries;
        int noneSamples;
    }

    private static final Map<Long, Episode> EPISODES = new HashMap<>();
    private static final Map<Long, Integer> NEXT_PREDICTION_TICK = new HashMap<>();
    private static int tickCounter;
    /** vehicleId → 最近一次实际观测（原始帧，未规范化） */
    private static final Map<Long, DoorSideObservation> LAST_SIDES = new HashMap<>();

    /** 是否已把 tick / 配置源注入 {@link DoorOpenCacheClient}（避免重复注册） */
    private static boolean installed;
    /** 注入失败过（类初始化异常等）：只记一次日志，不在 tick 里反复重试刷屏 */
    private static boolean installFailed;
    /** 观测异常只完整记录一次，避免每 tick 刷屏 */
    private static boolean errorLogged;
    private static Object lastWorld;
    private static long resourceVersion = -1;

    private DoorSideObserverMtr4() {
    }

    /**
     * 把本观测端接入 {@link DoorOpenCacheClient} 的客户端 tick（由 {@code MainClient.initClient()} 调用）。
     * <p>
     * 幂等：重复调用只会覆盖同样的两个方法引用；不依赖世界已加载，也就能在客户端初始化阶段调用。
     */
    public static void init() {
        if (installed || installFailed) return;
        try {
            DoorOpenCacheClient.init(DoorSideObserverMtr4::tick, DoorSideObserverMtr4::currentConfigs);
            installed = true;
            Main.LOGGER.info("[FangSu 开门缓存] MTR4 观测端已注册");
        } catch (Throwable t) {
            installFailed = true;
            Main.LOGGER.error("[FangSu 开门缓存] MTR4 观测端注册失败", t);
        }
    }

    /**
     * 由 {@link DoorOpenCacheClient} 的 ClientTickEvent 回调。
     * <p>
     * 未初始化时直接跑一遍 {@link #init()}（幂等），因此本方法自己不会因为
     * 「没人调过 init」而 NPE 或空转。
     */
    public static void tick() {
        ensureInstalled();
        tickCounter++;
        final Object world = net.minecraft.client.Minecraft.getInstance().level;
        final long version = com.fangsu.utils.ResourceUtil.getReloadVersion();
        if (world != lastWorld || version != resourceVersion) {
            EPISODES.clear();
            LAST_SIDES.clear();
            NEXT_PREDICTION_TICK.clear();
            DoorOpenCacheClient.clearPredictions();
            lastWorld = world;
            resourceVersion = version;
        }
        if (world == null) return;
        try {
            final MinecraftClientData data = MinecraftClientData.getInstance();
            if (data == null || data.vehicles == null) return;

            purgeRemovedVehicles(data.vehicles);
            for (final VehicleExtension vehicle : data.vehicles) {
                // 站台/资源未加载时每 10 tick 重试；已知方向不会重复扫描世界。
                if (tickCounter >= NEXT_PREDICTION_TICK.getOrDefault(vehicle.getId(), 0)) {
                    NEXT_PREDICTION_TICK.put(vehicle.getId(), tickCounter + 10);
                    DoorSidePredictorMtr4.predict(vehicle);
                }
                observeVehicle(vehicle);
            }
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                Main.LOGGER.error("[FangSu 开门缓存] 观测 tick 失败（后续同类错误不再记录）", t);
            } else {
                Main.debug("[FangSu 开门缓存] 观测 tick 失败：{}", t.toString());
            }
        }
    }

    /**
     * 最近一次实际观测到的开门方向（原始帧，未按反向规范化）。
     *
     * @return 长度 = 观测时车节数；从未观测到返回 {@code null}
     */
    public static DoorOpenRecord.DoorOpenSide[] lastSides(long vehicleId) {
        ensureInstalled();
        final DoorSideObservation observed = LAST_SIDES.get(vehicleId);
        return observed == null ? null : observed.sides();
    }

    /** 只读本站、当前反向状态下的实时观测。 */
    public static DoorOpenRecord.DoorOpenSide[] lastSides(long vehicleId, long platformId, boolean reversed) {
        final DoorSideObservation observed = LAST_SIDES.get(vehicleId);
        return observed == null ? null : observed.forStop(platformId, reversed);
    }

    public static long currentPlatformId(VehicleExtension vehicle) {
        return resolvePlatformId(vehicle);
    }

    /** 几何预测与 LCD 使用同一个前方停站点，不依赖线路详情是否已同步。 */
    public static long nextPlatformId(VehicleExtension vehicle) {
        final var stop = DoorSidePredictorMtr4.nextStop(vehicle);
        return stop == null ? 0 : stop.getSavedRailBaseId();
    }

    // ---------------------------------------------------------------- 单辆车

    private static void observeVehicle(VehicleExtension vehicle) {
        final long vehicleId = vehicle.getId();
        final Episode episode = EPISODES.computeIfAbsent(vehicleId, key -> new Episode());
        final double doorValue = vehicle.persistentVehicleData.getDoorValue();
        episode.lastDoorValue = doorValue;
        final long platform = resolvePlatformId(vehicle);
        final boolean reverse = vehicle.getReversed();
        if (episode.platformId != platform || episode.reversed != reverse) {
            episode.platformId = platform;
            episode.reversed = reverse;
            episode.reported = false;
            episode.retries = 0;
            episode.noneSamples = 0;
            LAST_SIDES.remove(vehicleId);
        }

        // 关门（含正在关）：复位本回合，等下一次开门
        if (doorValue <= DOOR_CLOSED_THRESHOLD) {
            episode.reported = false;
            episode.retries = 0;
            episode.noneSamples = 0;
            LAST_SIDES.remove(vehicleId);
            return;
        }
        // 门只开了一点、或者本回合已经判定过：不重复计算
        if (doorValue <= DOOR_OPEN_THRESHOLD || episode.reported) return;
        // 只有「门正在开」（倍率 > 0）才判定，避免关门途中误判
        if (vehicle.vehicleExtraData.getDoorMultiplier() <= 0) return;

        final long platformId = resolvePlatformId(vehicle);
        if (platformId == 0) {
            // 停站信息还没同步（或停在车辆段）：本回合不判定，等下一次开门
            episode.reported = true;
            return;
        }

        final DoorOpenRecord.DoorOpenSide[] observed = observeDoorSides(vehicle, false);
        if (observed == null) {
            // 车辆资源还没加载好：本 tick 视为失败，最多重试 MAX_RETRIES 次
            episode.retries++;
            if (episode.retries > MAX_RETRIES) {
                episode.reported = true;
                Main.LOGGER.warn("[FangSu 开门缓存] 车 {} 的车辆模型资源在 {} tick 内未就绪，本轮放弃观测",
                        vehicleId, MAX_RETRIES);
            }
            return;
        }

        // NONE 也实时可见；连续四次一致后允许同步 NONE，纠正停车预测而不是保留旧方向。
        LAST_SIDES.put(vehicleId, new DoorSideObservation(platformId, vehicle.getReversed(), observed));
        if (allNone(observed) && ++episode.noneSamples < 4) {
            return;
        }

        final VehicleExtraData extra = vehicle.vehicleExtraData;
        final String type = extra.immutableVehicleCars.isEmpty() ? "" : extra.immutableVehicleCars.get(0).getVehicleId();
        final int cars = observed.length;
        final int lengthMeters = (int) Math.round(extra.getTotalVehicleLength());
        final boolean reversed = vehicle.getReversed();

        // 规范化：保存「按发车车头、非反向帧」的左右，供预测时按匹配配置复用
        final DoorOpenRecord record = new DoorOpenRecord(
                observed, type, cars, lengthMeters, reversed, System.currentTimeMillis()
        ).normalized();

        // 先本地乐观写入，再上报服务端（契约要求的顺序）
        DoorOpenCacheClient.applyUpdate(platformId, record);
        DoorOpenCacheNet.sendObserveC2S(platformId, record);

        // LAST_SIDES 存原始帧（不翻转），LCD 的实时值直接用它
        LAST_SIDES.put(vehicleId, new DoorSideObservation(platformId, reversed, observed));
        episode.reported = true;
        episode.retries = 0;
        Main.debug("[FangSu 开门缓存] 车 {} 站台 {} 观测到 {}（reversed={}）",
                vehicleId, platformId, describe(observed), reversed);
    }

    /**
     * 用 MTR 自己的几何数据判定逐车节开门方向。
     *
     * @return 长度 = 车节数；任一车节的车辆资源未就绪时返回 {@code null}（本次失败，交上层重试）
     */
    static DoorOpenRecord.DoorOpenSide[] observeDoorSides(Vehicle vehicle, boolean prediction) {
        // 注意：必须用 getVehicleCarsAndPositions()，不能用 getSmoothedVehicleCarsAndPositions
        // （后者会改写 persistentVehicleData 的平滑进度，污染 MTR 自己的渲染状态）。
        // 返回结构（MTR 4.0.5 实际签名，javap 核实）：每节车 → 每转向架 → (Vector, Vector) 两个接触点。
        final ObjectArrayList<ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<ObjectObjectImmutablePair<Vector, Vector>>>> carsAndPositions =
                vehicle.getVehicleCarsAndPositions();
        final int totalCars = carsAndPositions.size();
        if (totalCars <= 0) return null;

        final TransportMode transportMode = vehicle.getTransportMode();
        final boolean hasPitch = transportMode.hasPitchAscending || transportMode.hasPitchDescending;

        // 第一遍：构造每节车的 PnR 并取车辆资源；任一节资源为空 → 整体失败
        final ObjectArrayList<PositionAndRotation> carPositionAndRotations = new ObjectArrayList<>();
        final ObjectArrayList<VehicleResourceCache> carResourceCaches = new ObjectArrayList<>();
        for (int carIndex = 0; carIndex < totalCars; carIndex++) {
            final ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<ObjectObjectImmutablePair<Vector, Vector>>> carAndPositions =
                    carsAndPositions.get(carIndex);
            final ObjectArrayList<PositionAndRotation> bogiePositionAndRotations = new ObjectArrayList<>();
            for (final ObjectObjectImmutablePair<Vector, Vector> bogiePosition : carAndPositions.right()) {
                // 与 MTR 渲染一致：一个转向架的两点取出一条 PnR（hasPitch 恒为 true）
                bogiePositionAndRotations.add(new PositionAndRotation(
                        bogiePosition.left(),
                        bogiePosition.right(),
                        true
                ));
            }
            carPositionAndRotations.add(new PositionAndRotation(
                    bogiePositionAndRotations, carAndPositions.left(), hasPitch
            ));

            final VehicleResourceCache[] cacheHolder = new VehicleResourceCache[1];
            // 循环变量不是 effectively final，lambda 里要用一份拷贝
            final int cacheCarIndex = carIndex;
            CustomResourceLoader.getVehicleById(transportMode, carAndPositions.left().getVehicleId(),
                    pair -> cacheHolder[0] = pair.left().getCachedVehicleResource(cacheCarIndex, totalCars, false));
            if (cacheHolder[0] == null) return null;
            carResourceCaches.add(cacheHolder[0]);
        }

        // 第二遍：逐 doorway 判定，局部 X < 0 → 左侧，> 0 → 右侧，跨 0 → 两侧
        final DoorOpenRecord.DoorOpenSide[] sides = new DoorOpenRecord.DoorOpenSide[totalCars];
        for (int carIndex = 0; carIndex < totalCars; carIndex++) {
            boolean left = false;
            boolean right = false;
            for (final Box doorway : carResourceCaches.get(carIndex).doorways) {
                final Boolean canOpen = prediction
                        ? DoorSidePredictorMtr4.canOpenDoors(doorway, carPositionAndRotations.get(carIndex))
                        : Boolean.valueOf(RenderVehicleHelper.canOpenDoors(doorway, carPositionAndRotations.get(carIndex), DOOR_CHECK_VALUE));
                if (canOpen == null) return null;
                if (!canOpen) {
                    continue;
                }
                if (doorway.getMaxXMapped() <= 0) {
                    left = true;
                } else if (doorway.getMinXMapped() >= 0) {
                    right = true;
                } else {
                    // doorway 横跨车体中心线：两侧都算
                    left = true;
                    right = true;
                }
            }
            sides[carIndex] = DoorOpenRecord.DoorOpenSide.of(left, right);
        }
        return sides;
    }

    // ---------------------------------------------------------------- 辅助

    /** 站点 id：优先「当前停靠站台」，退「下一站台」；取不到或等于车辆段 id 时返回 0 */
    private static long resolvePlatformId(VehicleExtension vehicle) {
        final var stop = DoorSidePredictorMtr4.nextStop(vehicle);
        final double progress = ((com.fangsu.mixin.VehicleDoorPredictionAccessor) vehicle).fangsu$getRailProgress();
        if (stop != null && Math.abs(stop.getEndDistance() - progress) < 3) return stop.getSavedRailBaseId();
        final VehicleExtraData extra = vehicle.vehicleExtraData;
        long platformId = extra.getThisPlatformId();
        if (platformId == 0) {
            platformId = extra.getNextPlatformId();
        }
        if (platformId == 0 || platformId == extra.getSidingId()) return 0;
        return platformId;
    }

    /** 清掉已经不存在的车辆对应的 episode / lastSides，避免长期累积 */
    private static void purgeRemovedVehicles(Set<VehicleExtension> vehicles) {
        if (EPISODES.isEmpty() && LAST_SIDES.isEmpty() && NEXT_PREDICTION_TICK.isEmpty()) return;
        final Set<Long> alive = new HashSet<>();
        for (final VehicleExtension vehicle : vehicles) {
            alive.add(vehicle.getId());
        }
        EPISODES.keySet().removeIf(id -> !alive.contains(id));
        LAST_SIDES.keySet().removeIf(id -> !alive.contains(id));
        NEXT_PREDICTION_TICK.keySet().removeIf(id -> !alive.contains(id));
    }

    private static boolean allNone(DoorOpenRecord.DoorOpenSide[] sides) {
        for (final DoorOpenRecord.DoorOpenSide side : sides) {
            if (side != DoorOpenRecord.DoorOpenSide.NONE) return false;
        }
        return true;
    }

    private static String describe(DoorOpenRecord.DoorOpenSide[] sides) {
        final StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < sides.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(sides[i]);
        }
        return builder.append(']').toString();
    }

    /**
     * 当前客户端所有车辆的配置（车型 + 编组 + 总长），供缓存层按需拉取快照。
     * <p>
     * 同配置只保留一条；任何一辆车的数据不完整就跳过它，不影响其他车。
     */
    private static List<DoorOpenCacheNet.ConfigKey> currentConfigs() {
        final List<DoorOpenCacheNet.ConfigKey> configs = new ArrayList<>();
        final MinecraftClientData data = MinecraftClientData.getInstance();
        if (data == null || data.vehicles == null) return configs;

        final Set<String> seen = new HashSet<>();
        for (final VehicleExtension vehicle : data.vehicles) {
            try {
                final VehicleExtraData extra = vehicle.vehicleExtraData;
                if (extra.immutableVehicleCars.isEmpty()) continue;
                final String type = extra.immutableVehicleCars.get(0).getVehicleId();
                if (type == null || type.isEmpty()) continue;
                final int cars = extra.immutableVehicleCars.size();
                final int lengthMeters = (int) Math.round(extra.getTotalVehicleLength());
                if (seen.add(type + "|" + cars + "|" + lengthMeters)) {
                    configs.add(new DoorOpenCacheNet.ConfigKey(type, cars, lengthMeters));
                }
            } catch (Throwable t) {
                Main.debug("[FangSu 开门缓存] 读取车辆配置失败：{}", t.toString());
            }
        }
        return configs;
    }

    /** 观测端自身的延迟兜底安装：LCD 查询 lastSides() 时顺带把 tick 注入缓存层 */
    private static void ensureInstalled() {
        if (!installed) init();
    }
}
