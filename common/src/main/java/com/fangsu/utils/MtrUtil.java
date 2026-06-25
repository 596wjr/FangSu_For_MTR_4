package com.fangsu.utils;

import com.fangsu.mtr.LocalRoute;
import com.fangsu.scripting.TextUtil;
import net.minecraft.client.Minecraft;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectAVLTreeSet;
import org.mtr.mod.block.BlockNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.mtr.core.data.*;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.Station;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.ArrivalsCacheClient;
//#if MC_VERSION >= 11903
import org.joml.Vector3f;
import org.joml.Vector3fc;
//#else
//$$import com.mojang.math.Vector3f;
//#endif

import java.util.*;

public class MtrUtil {
    private Map<Long, Route> requestIdToRoute = new HashMap<>();

    /**
     * 根据坐标与搜索范围获取最近的站台。
     */
    @Nullable
    public static Platform getPlatformAt(Vector3f pos, int radius, int lower, int upper) {
        final BlockPos blockPos = toBlockPos(pos);
        final Station station = getStationAt(pos);
        if (station == null) return null;

        Platform closestPlatform = null;
        long closestDistance = Long.MAX_VALUE;
        for (final Platform platform : station.savedRails) {
            final Position midPos = platform.getMidPosition();
            final long distance = Math.abs(midPos.getX() - blockPos.getX()) + Math.abs(midPos.getZ() - blockPos.getZ());
            if (distance < closestDistance) {
                closestDistance = distance;
                closestPlatform = platform;
            }
        }
        return closestPlatform;
    }

    /**
     * 获取当前位置对应的车站。
     */
    @Nullable
    public static Station getStationAt(Vector3f pos) {
        return getStationAt(toBlockPos(pos));
    }

    /**
     * 获取当前位置对应的车站（仅检查 X/Z，忽略 Y 坐标）。
     */
    @Nullable
    public static Station getStationAt(BlockPos blockPos) {
        final int x = blockPos.getX();
        final int z = blockPos.getZ();
        final long[] closestDist = {Long.MAX_VALUE};
        final Station[] closestStation = {null};
        for (final Station station : MinecraftClientData.getInstance().stations) {
            if (x >= station.getMinX() && x <= station.getMaxX() && z >= station.getMinZ() && z <= station.getMaxZ()) {
                // 若玩家在多个车站重叠区域，选中心点最近的
                final long cx = (station.getMinX() + station.getMaxX()) / 2;
                final long cz = (station.getMinZ() + station.getMaxZ()) / 2;
                final long dist = Math.abs(cx - x) + Math.abs(cz - z);
                if (dist < closestDist[0]) {
                    closestDist[0] = dist;
                    closestStation[0] = station;
                }
            }
        }
        return closestStation[0];
    }

    /**
     * 获取附近的轨道节点位置。
     */
    @Nullable
    public static Vector3f getNodeAt(Vector3f vPos, Float fFacing) {
        BlockPos pos = toBlockPos(vPos);
        Direction facing = Direction.fromYRot(fFacing);
        BlockGetter world = Minecraft.getInstance().level;
        final int[] checkDistance = {0, 1, -1, 2, -2, 3, -3, 4, -4};
        for (final int z : checkDistance) {
            for (final int x : checkDistance) {
                for (int y = -5; y <= 0; y++) {
                    final BlockPos checkPos = pos.above(y).relative(facing.getClockWise(), x).relative(facing, z);
                    final BlockState checkState = world.getBlockState(checkPos);
                    if (checkState.getBlock() instanceof BlockNode) {
                        //#if MC_VERSION >= 11903
                        return new Vector3f((Vector3fc) checkPos);
                        //#else
                        //$$ return new Vector3f((float) checkPos.getX(), (float) checkPos.getY(), (float) checkPos.getZ());
                        //#endif
                    }
                }
            }
        }
        return null;
    }

    /**
     * 根据站台 ID 查找站台。
     */
    @Nullable
    public static Platform getPlatformById(long platformId) {
        return MinecraftClientData.getInstance().platformIdMap.get(platformId);
    }

    /**
     * 根据车站 ID 查找车站。
     */
    @Nullable
    public static Station getStationById(long stationId) {
        return MinecraftClientData.getInstance().stationIdMap.get(stationId);
    }

    /**
     * 根据路线 ID 查找路线。
     */
    @Nullable
    @Deprecated
    public static Route getRouteByIdMtr(long routeId) {
        return MinecraftClientData.getInstance().routeIdMap.get(routeId);
    }

    /**
     * 根据路线 ID 查找路线。
     */
    @Nullable
    public static LocalRoute getRouteById(long routeId) {
        final Route route = MinecraftClientData.getInstance().routeIdMap.get(routeId);
        if (route != null) return new LocalRoute(route);
        final SimplifiedRoute simplifiedRoute = MinecraftClientData.getInstance().simplifiedRouteIdMap.get(routeId);
        return simplifiedRoute != null ? new LocalRoute(simplifiedRoute) : null;
    }

    public static List<LocalRoute> getRouteByName(String routeName) {
        final String compareName = TextUtil.getNonExtraParts(routeName);
        final Set<Long> addedIds = new HashSet<>();
        final List<LocalRoute> routes = new ArrayList<>();
        // 先查完整 Route
        for (final Route r : MinecraftClientData.getInstance().routes) {
            final String currentRouteName = TextUtil.getNonExtraParts(r.getName());
            if (compareName.equals(currentRouteName)) {
                routes.add(new LocalRoute(r));
                addedIds.add(r.getId());
            }
        }
        // 再查 SimplifiedRoute 补充
        for (final SimplifiedRoute sr : MinecraftClientData.getInstance().simplifiedRoutes) {
            if (!addedIds.contains(sr.getId())) {
                final String currentRouteName = TextUtil.getNonExtraParts(sr.getName());
                if (compareName.equals(currentRouteName)) {
                    routes.add(new LocalRoute(sr));
                }
            }
        }
        return routes;
    }

    /**
     * 获取站台所属车站。
     */
    @Nullable
    public static Station getStationByPlatform(Platform platform) {
        return platform == null ? null : platform.area;
    }

    /**
     * 获取车站的所有站台。
     */
    @Nullable
    public static List<Platform> getPlatformByStation(Station station) {
        if (station == null) return null;
        return new ArrayList<>(station.savedRails);
    }

    /**
     * 获取站台对应的所有路线（优先使用 SimplifiedRoute，客户端数据始终完整）。
     */
    @Nullable
    public static List<LocalRoute> getRouteByPlatform(Platform platform) {
        if (platform == null) return null;
        final long platformId = platform.getId();
        final List<LocalRoute> routes = new ArrayList<>();
        for (final SimplifiedRoute simplifiedRoute : MinecraftClientData.getInstance().simplifiedRoutes) {
            if (simplifiedRoute.getPlatformIndex(platformId) >= 0) {
                // 优先使用完整 Route 数据，回退到 SimplifiedRoute
                final Route fullRoute = MinecraftClientData.getInstance().routeIdMap.get(simplifiedRoute.getId());
                if (fullRoute != null) {
                    routes.add(new LocalRoute(fullRoute));
                } else {
                    routes.add(new LocalRoute(simplifiedRoute));
                }
            }
        }
        return routes;
    }

    /**
     * 获取路线的终点站名称（优先使用 SimplifiedRoute 回退）。
     */
    public static String getDestinationByRoute(Route route) {
        if (route == null) return "undefined";
        try {
            // 先从 SimplifiedRoute 获取终点站
            final SimplifiedRoute simplifiedRoute = MinecraftClientData.getInstance().simplifiedRouteIdMap.get(route.getId());
            if (simplifiedRoute != null) {
                final var platforms = simplifiedRoute.getPlatforms();
                if (!platforms.isEmpty()) {
                    final String dest = platforms.get(platforms.size() - 1).getStationName();
                    if (dest != null && !dest.isEmpty()) return dest;
                }
            }
            // 回退：从 Route.getRoutePlatforms 获取
            final List<RoutePlatformData> routePlatforms = route.getRoutePlatforms();
            if (routePlatforms.isEmpty()) return "未命名|Undefined";
            final RoutePlatformData last = routePlatforms.get(routePlatforms.size() - 1);
            final Platform platform = last.getPlatform();
            if (platform != null && platform.area != null) {
                return platform.area.getName();
            }
            return "未命名|Undefined";
        } catch (Exception ignored) {
            return "undefined";
        }
    }

    /**
     * 获取路线的终点站名称（LocalRoute 版本，优先使用 SimplifiedRoute）。
     */
    public static String getDestinationByRoute(LocalRoute route) {
        if (route == null) return "undefined";
        try {
            // 优先从 SimplifiedRoute 获取（远端数据也完整）
            final SimplifiedRoute sr = MinecraftClientData.getInstance().simplifiedRouteIdMap.get(route.id);
            if (sr != null) {
                final var platforms = sr.getPlatforms();
                if (!platforms.isEmpty()) {
                    final String stationName = platforms.get(platforms.size() - 1).getStationName();
                    if (stationName != null && !stationName.isEmpty()) return stationName;
                }
            }
            // 回退：通过 platformId 查找 Platform.area
            final LocalRoute.RoutePlatform destinationRoutePlatform = route.platformIds.get(route.platformIds.size() - 1);
            final Platform destinationPlatform = getPlatformById(destinationRoutePlatform.platformId);
            if (destinationPlatform != null && destinationPlatform.area != null) {
                return destinationPlatform.area.getName();
            }
            return "未命名|Undefined";
        } catch (Exception ignored) {
            return "undefined";
        }
    }

    public static boolean isAllDestination(Platform platform) {
        if (platform == null) return true;
        final List<LocalRoute> routes = getRouteByPlatform(platform);
        if (routes == null || routes.isEmpty()) return true;
        for (final LocalRoute route : routes) {
            if (!isDestination(route, platform)) return false;
        }
        return true;
    }

    public static boolean isDestination(LocalRoute route, Platform platform) {
        if (route == null || platform == null) return false;
        return route.platformIds.get(route.platformIds.size() - 1).platformId == platform.getId();
    }

    // ============ SimplifiedRoute 辅助方法 ============

    /**
     * 获取所有 SimplifiedRoute（客户端始终完整）。
     */
    public static ObjectAVLTreeSet<SimplifiedRoute> getSimplifiedRoutes() {
        return MinecraftClientData.getInstance().simplifiedRoutes;
    }

    /**
     * 根据路线 ID 获取 SimplifiedRoute。
     */
    @Nullable
    public static SimplifiedRoute getSimplifiedRouteById(long routeId) {
        return MinecraftClientData.getInstance().simplifiedRouteIdMap.get(routeId);
    }

    /**
     * 根据站台获取所有终点站名称（去重且按字典序拼接）。
     */
    public static String getDestinationByPlatform(Platform platform) {
        if (platform == null) return "undefined";
        try {
            final List<LocalRoute> routes = getRouteByPlatform(platform);
            if (routes == null || routes.isEmpty()) return "";
            final Set<String> destinationSet = new HashSet<>();
            for (final LocalRoute route : routes) {
                destinationSet.add(getDestinationByRoute(route));
            }
            final List<String> destinations = new ArrayList<>(destinationSet);
            destinations.sort(String::compareTo);
            return String.join("/", destinations);
        } catch (Exception ignored) {
            return "undefined";
        }
    }

    /**
     * 使用 MTR4 的 ArrivalsCacheClient 获取站台到站信息。
     */
    public static List<PidsArrivalInfo> getPidsArrivalInfoList(List<Long> platformIds) {
        final List<PidsArrivalInfo> arrivalInfoList = new ArrayList<>();
        if (platformIds == null || platformIds.isEmpty()) return arrivalInfoList;

        try {
            final LongAVLTreeSet platformIdSet = new LongAVLTreeSet();
            platformIdSet.addAll(platformIds);

            final var arrivals = ArrivalsCacheClient.INSTANCE.requestArrivals(platformIdSet);
            for (final ArrivalResponse response : arrivals) {
                final long routeId = response.getRouteId();
                if (routeId == 0) continue;

                // 获取路线站点列表和当前站台索引
                final SimplifiedRoute sr = getSimplifiedRouteById(routeId);
                final int currentStationIndex = sr != null ? sr.getPlatformIndex(response.getPlatformId()) : 0;

                // 收集所有途经站名
                final List<String> stationNames = new ArrayList<>();
                if (sr != null) {
                    for (final var sp : sr.getPlatforms()) {
                        final String stnName = sp.getStationName();
                        if (stnName != null && !stnName.isEmpty()) {
                            stationNames.add(stnName);
                        }
                    }
                }

                arrivalInfoList.add(new PidsArrivalInfo(
                        response.getArrival(),
                        (int) response.getCarCount(),
                        routeId,
                        currentStationIndex,
                        response.getDestination(),
                        response.getDestination(),
                        stationNames,
                        response.getPlatformName()
                ));
            }

            arrivalInfoList.sort(Comparator.comparingLong(PidsArrivalInfo::arrivalMillis));
        } catch (Exception e) {
            // ArrivalsCacheClient 只在客户端可用
        }
        return arrivalInfoList;
    }

    public static final class PidsArrivalInfo {
        public final long arrivalMillis;
        public final int trainCars;
        public final long routeId;
        public final int currentStationIndex;
        public final String destination;
        public final String customDestination;
        public final List<String> stationNames;
        public final String currentPlatformName;

        public PidsArrivalInfo(
                long arrivalMillis,
                int trainCars,
                long routeId,
                int currentStationIndex,
                String destination,
                String customDestination,
                List<String> stationNames,
                String currentPlatformName
        ) {
            this.arrivalMillis = arrivalMillis;
            this.trainCars = trainCars;
            this.routeId = routeId;
            this.currentStationIndex = currentStationIndex;
            this.destination = destination;
            this.customDestination = customDestination;
            this.stationNames = stationNames;
            this.currentPlatformName = currentPlatformName;
        }

        public long arrivalMillis() {
            return arrivalMillis;
        }

        public int trainCars() {
            return trainCars;
        }

        public long routeId() {
            return routeId;
        }

        public int currentStationIndex() {
            return currentStationIndex;
        }

        public String destination() {
            return destination;
        }

        public String customDestination() {
            return customDestination;
        }

        public List<String> stationNames() {
            return stationNames;
        }

        public String currentPlatformName() {
            return currentPlatformName;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) return true;
            if (obj == null || obj.getClass() != this.getClass()) return false;
            var that = (PidsArrivalInfo) obj;
            return this.arrivalMillis == that.arrivalMillis &&
                    this.trainCars == that.trainCars &&
                    this.routeId == that.routeId &&
                    this.currentStationIndex == that.currentStationIndex &&
                    Objects.equals(this.destination, that.destination) &&
                    Objects.equals(this.customDestination, that.customDestination) &&
                    Objects.equals(this.stationNames, that.stationNames) &&
                    Objects.equals(this.currentPlatformName, that.currentPlatformName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(arrivalMillis, trainCars, routeId, currentStationIndex, destination, customDestination, stationNames, currentPlatformName);
        }

        @Override
        public String toString() {
            return "PidsArrivalInfo[" +
                    "arrivalMillis=" + arrivalMillis + ", " +
                    "trainCars=" + trainCars + ", " +
                    "routeId=" + routeId + ", " +
                    "currentStationIndex=" + currentStationIndex + ", " +
                    "destination=" + destination + ", " +
                    "customDestination=" + customDestination + ", " +
                    "stationNames=" + stationNames + ", " +
                    "currentPlatformName=" + currentPlatformName + ']';
        }
    }

    /**
     * 获取 BlockPos 的中心点 Vector3f，兼容 1.18.2（无 getCenter()）。
     */
    public static Vector3f getCenterVector3f(BlockPos pos) {
        //#if MC_VERSION >= 11903
        return new Vector3f(pos.getCenter().toVector3f());
        //#else
        //$$ return new Vector3f(pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f);
        //#endif
    }

    private static BlockPos toBlockPos(Vector3f pos) {
        //#if MC_VERSION >= 11903
        return new BlockPos(new Vec3i((int) pos.x, (int) pos.y, (int) pos.z));
        //#else
        //$$ return new BlockPos(new Vec3i((int) pos.x(), (int) pos.y(), (int) pos.z()));
        //#endif
    }
}
