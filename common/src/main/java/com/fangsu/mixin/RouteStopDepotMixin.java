package com.fangsu.mixin;

import com.fangsu.mtr.RouteStopStatus;
import com.fangsu.mtr.RouteServices;
import com.fangsu.mtr.StationOpening;
import org.mtr.core.data.Route;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.fangsu.mtr.RouteBypass;
import com.fangsu.mtr.ServiceStop;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Platform;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = Depot.class, remap = false)
public abstract class RouteStopDepotMixin {
    @Shadow @Final private ObjectArrayList<?> platformsInRoute;

    @Inject(method = "writeRouteCache", at = @At("TAIL"))
    private void fangsu$rejectInvalidSections(Long2ObjectOpenHashMap<Route> routes, CallbackInfo ci) {
        for (final long routeId : ((Depot) (Object) this).getRouteIds()) {
            final var route = routes.get(routeId);
            final var relation = route == null ? null : RouteServices.relation(route);
            if (relation != null && !RouteServices.hasRunningSection(routes.get(relation.parentRoute()), route)) {
                platformsInRoute.clear();
                return;
            }
        }
    }

    @WrapOperation(method = "writeRouteCache",
            at = @At(value = "FIELD", target = "Lorg/mtr/core/data/RoutePlatformData;platform:Lorg/mtr/core/data/Platform;"))
    private Platform fangsu$copyPathStop(RoutePlatformData stop, Operation<Platform> original) {
        final Platform platform = original.call(stop);
        if (platform == null) return null;
        final var status = StationOpening.pathStatus(stop, platform.area);
        if (status == RouteStopStatus.NORMAL) return platform;
        final var data = ((DepotDataAccess) (Object) this).fangsu$getData();
        final var metadata = ServiceStop.get(stop);
        final var bypass = metadata == null ? null : metadata.bypass();
        final var payload = Utilities.getJsonObjectFromData(platform);
        if (status == RouteStopStatus.SKIPPED && bypass != null && metadata.express()
                && RouteBypass.sameStation(platform.area, bypass) && RouteBypass.settings(data, bypass) != null) {
            final var rail = new Platform(RouteBypass.position(bypass.start()), RouteBypass.position(bypass.end()),
                    platform.getTransportMode(), data);
            final var railPayload = Utilities.getJsonObjectFromData(rail);
            payload.addProperty("id", RouteBypass.waypointId(bypass));
            payload.add("position1", railPayload.get("position1"));
            payload.add("position2", railPayload.get("position2"));
        }
        final var waypoint = new Platform(new JsonReader(payload), data);
        waypoint.area = platform.area;
        RouteStopStatus.set(waypoint, status);
        if (metadata != null) {
            ((ServiceStop.Holder) (Object) waypoint).fangsu$setServiceStop(metadata);
        }
        return waypoint;
    }
}
