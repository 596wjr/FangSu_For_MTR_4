package com.fangsu.train;

import com.fangsu.mtr.RouteStopStatus;
import com.fangsu.utils.MtrUtil;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleDataCache;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleWrapper;
import org.mtr.core.data.PathData;
import org.mtr.core.data.VehicleExtraData;

import java.util.List;

public final class JcmStopLookup {
    private JcmStopLookup() {}

    public static PathData nextPhysicalStop(VehicleExtraData extra, double progress) {
        for (final PathData segment : extra.immutablePath) {
            final long platformId = segment.getSavedRailBaseId();
            // 客户端中途收到轨段时，轨道对象还可能没有恢复站台属性。
            if (segment.getEndDistance() + 0.001 >= progress && segment.getDwellTime() > 0
                    && platformId != 0 && platformId != extra.getSidingId()) {
                return segment;
            }
        }
        return null;
    }

    public static boolean matchesPhysicalStop(VehicleWrapper.Stop stop, PathData physical) {
        return physical == null || stop != null && stop.platform != null
                && stop.platform.getId() == physical.getSavedRailBaseId()
                && (stop.distance < 0 || Math.abs(stop.distance - physical.getEndDistance()) < 0.01);
    }

    /** 中途上车时，完整停车表可能还未返回。先用实际轨段，未同步到远处轨段时用 MTR 的前方站台。 */
    public static int limitedIndex(VehicleExtraData extra, double progress, double tolerance, List<VehicleWrapper.Stop> stops) {
        final PathData physical = nextPhysicalStop(extra, progress - tolerance);
        if (physical != null) {
            final int index = findPlatform(stops, physical.getSavedRailBaseId(), extra.getThisRouteId());
            if (index >= 0) return index;
        }
        final int index = findFromWaypoint(stops, extra.getThisRouteId(), extra.getThisPlatformId());
        return index >= 0 ? index : findFromWaypoint(stops, extra.getNextRouteId(), extra.getNextPlatformId());
    }

    private static int findFromWaypoint(List<VehicleWrapper.Stop> stops, long routeId, long platformId) {
        if (platformId == 0) return -1;
        final var local = MtrUtil.getSimplifiedRouteById(routeId);
        final var route = local == null ? VehicleDataCache.mtrData.routeIdMap.get(routeId) : local;
        if (route == null) return findPlatform(stops, platformId, routeId);

        final int start = route.getPlatformIndex(platformId);
        if (start < 0) return findPlatform(stops, platformId, routeId);
        for (int index = start; index < route.getPlatforms().size(); index++) {
            final var waypoint = route.getPlatforms().get(index);
            if (RouteStopStatus.of(waypoint) == RouteStopStatus.NORMAL) {
                return findPlatform(stops, waypoint.getPlatformId(), routeId);
            }
        }
        return -1;
    }

    private static int findPlatform(List<VehicleWrapper.Stop> stops, long platformId, long routeId) {
        int fallback = -1;
        for (int index = 0; index < stops.size(); index++) {
            final var stop = stops.get(index);
            if (stop.platform != null && stop.platform.getId() == platformId) {
                if (stop.route != null && stop.route.getId() == routeId) return index;
                if (fallback < 0) fallback = index;
            }
        }
        return fallback;
    }
}
