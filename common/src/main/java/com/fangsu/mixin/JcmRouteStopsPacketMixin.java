package com.fangsu.mixin;

import com.fangsu.mtr.RouteBypass;
import com.fangsu.mtr.RouteStopStatus;
import com.fangsu.mtr.ServiceStop;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleDataCache;
import org.mtr.core.data.Depot;
import org.mtr.core.data.PathData;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashMap;

@Pseudo
@Mixin(targets = "com.lx862.jcm.mod.network.scripting.RequestStopsDataC2SPacket", remap = false)
public abstract class JcmRouteStopsPacketMixin {
    @WrapOperation(method = "lambda$runServer$1", at = @At(value = "FIELD",
            target = "Lorg/mtr/core/data/VehicleExtraData;immutablePath:Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectImmutableList;"),
            require = 0)
    private ObjectImmutableList<PathData> fangsu$buildServiceStops(VehicleExtraData extra,
            Operation<ObjectImmutableList<PathData>> original, @Local Depot depot,
            @Local VehicleDataCache.SimplifiedStopsData response) {
        final var path = original.call(extra);
        if (depot.routes.stream().noneMatch(route -> route.getRoutePlatforms().stream()
                .anyMatch(stop -> RouteStopStatus.of(stop) != RouteStopStatus.NORMAL))) {
            return path;
        }

        // 路径的 stopIndex 与车厂的物理站序对齐，包括零停车时间的通过点。
        // JCM 原来的停车计数会把 C 的里程写给 B，甚至不发送整段 A–B–C。
        final var arrivals = new HashMap<Integer, Integer>();
        for (int index = 0; index < path.size(); index++) {
            final var segment = path.get(index);
            if (segment.getSavedRailBaseId() != 0 && segment.getDwellTime() > 0 && segment.getRail().isPlatform()) {
                arrivals.putIfAbsent(segment.getStopIndex(), index);
            }
        }

        int waypointIndex = -1;
        long previousWaypoint = 0;
        for (final var route : depot.routes) {
            final var routeStops = new VehicleDataCache.RouteStopsData(route.getId());
            int lastAddedWaypoint = -1;
            for (int index = 0; index < route.getRoutePlatforms().size(); index++) {
                final var stop = route.getRoutePlatforms().get(index);
                final var platform = stop.platform;
                if (platform == null) continue;
                final long platformId = platform.getId();
                final var status = RouteStopStatus.of(stop);
                final var metadata = ServiceStop.get(stop);
                final long waypoint = status == RouteStopStatus.SKIPPED && metadata != null && metadata.bypass() != null
                        ? RouteBypass.waypointId(metadata.bypass()) : platformId;
                if (waypoint != previousWaypoint) {
                    waypointIndex++;
                    previousWaypoint = waypoint;
                }

                final Integer pathIndex = arrivals.get(waypointIndex);
                if (status != RouteStopStatus.NORMAL || waypointIndex == lastAddedWaypoint || pathIndex == null) continue;
                final PathData arrival = path.get(pathIndex);
                if (arrival.getSavedRailBaseId() != platformId) continue;
                final long stationId = platform.area == null ? 0 : platform.area.getId();
                final boolean turnsBack = pathIndex + 1 < path.size() && path.get(pathIndex + 1).isOppositeRail(arrival);
                routeStops.addStop(new VehicleDataCache.SimplifiedStop(route.getDestination(index),
                        stop.getCustomDestination(), stationId, platformId, arrival.getEndDistance(), turnsBack));
                lastAddedWaypoint = waypointIndex;
            }
            if (!routeStops.stops.isEmpty()) response.addRouteStopsData(routeStops);
        }
        // 保留 JCM 原来的发包入口，避免再次按未经筛选的站序填充响应。
        return ObjectImmutableList.of();
    }
}
