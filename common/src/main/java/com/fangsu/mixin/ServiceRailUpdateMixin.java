package com.fangsu.mixin;

import com.fangsu.mtr.rail.ServiceRail;
import com.fangsu.mtr.RouteStopStatus;
import com.fangsu.mtr.RouteServices;
import com.fangsu.mtr.RouteBypass;
import com.fangsu.mtr.ServiceStop;
import com.fangsu.network.ServiceRailPackets;
import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Route;
import org.mtr.core.data.Rail;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.core.operation.UpdateDataResponse;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

@Mixin(value = UpdateDataRequest.class, remap = false)
public abstract class ServiceRailUpdateMixin {
    @Shadow @Final private Data data;

    @Inject(method = "update()Lorg/mtr/core/operation/UpdateDataResponse;", at = @At("HEAD"))
    private void fangsu$prepareGeneration(CallbackInfoReturnable<UpdateDataResponse> cir) {
        if (!(data instanceof Simulator simulator)) {
            return;
        }
        final var changed = new HashSet<String>();
        final var access = (ServiceRailUpdateAccess) (Object) this;
        for (final Rail rail : access.fangsu$getRails()) {
            final var previous = ServiceRail.get(data.railIdMap.get(rail.getHexId()));
            final var next = ServiceRail.get(rail);
            if (rail.isValid() && !Objects.equals(previous, next)
                    && (previous == null || next == null || previous.kind() != next.kind() || previous.value() != next.value())) {
                changed.add(rail.getHexId());
            }
        }
        if (!changed.isEmpty()) {
            simulator.run(() -> ServiceRailPackets.regenerate(simulator, changed));
        }
        final var changedRoutes = new HashSet<Long>();
        final var submitted = new LinkedHashMap<Long, Route>();
        access.fangsu$getRoutes().forEach(route -> submitted.put(route.getId(), route));
        for (final var route : List.copyOf(submitted.values())) {
            final var relation = RouteServices.relation(route);
            if (relation != null) {
                final var parent = submitted.getOrDefault(relation.parentRoute(), data.routeIdMap.get(relation.parentRoute()));
                if (parent != null) {
                    RouteServices.synchronize(parent, route, this::fangsu$stationForPlatform, this::fangsu$validBypass);
                }
            }
        }
        for (final var stored : simulator.routes) {
            if (submitted.containsKey(stored.getId())) {
                continue;
            }
            final var relation = RouteServices.relation(stored);
            final var parent = relation == null ? null : submitted.get(relation.parentRoute());
            if (parent != null) {
                final var child = new Route(new org.mtr.core.serializer.JsonReader(org.mtr.core.tool.Utilities.getJsonObjectFromData(stored)), data);
                if (RouteServices.synchronize(parent, child, this::fangsu$stationForPlatform, this::fangsu$validBypass)) {
                    access.fangsu$getRoutes().add(child);
                }
            }
        }
        for (final var route : access.fangsu$getRoutes()) {
            final var oldRoute = data.routeIdMap.get(route.getId());
            if (oldRoute != null && !fangsu$routeStops(oldRoute).equals(fangsu$routeStops(route))) {
                changedRoutes.add(route.getId());
            }
        }
        if (!changedRoutes.isEmpty()) {
            simulator.run(() -> {
                final var depots = new ObjectArrayList<Depot>();
                for (final var depot : simulator.depots) {
                    if (depot.getRouteIds().stream().anyMatch(changedRoutes::contains)) {
                        depots.add(depot);
                    }
                }
                if (!depots.isEmpty()) {
                    Depot.generateDepots(simulator, depots);
                }
            });
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private boolean fangsu$validBypass(long id, ServiceStop.Bypass bypass) {
        final var platform = data.platformIdMap.get(id);
        return platform != null && RouteBypass.sameStation(platform.area, bypass) && RouteBypass.settings(data, bypass) != null;
    }

    @org.spongepowered.asm.mixin.Unique
    private long fangsu$stationForPlatform(long id) {
        final var platform = data.platformIdMap.get(id);
        return platform == null || platform.area == null ? 0 : platform.area.getId();
    }

    @org.spongepowered.asm.mixin.Unique
    private static List<RouteStop> fangsu$routeStops(Route route) {
        return route.getRoutePlatforms().stream().map(stop -> new RouteStop(
                ((RoutePlatformDataAccess) (Object) stop).fangsu$getPlatformId(),
                RouteStopStatus.of(stop), ServiceStop.get(stop))).toList();
    }

    public record RouteStop(long platformId, RouteStopStatus status, ServiceStop metadata) {}
}
