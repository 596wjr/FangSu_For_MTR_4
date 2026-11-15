package com.fangsu.mixin;

import com.fangsu.mtr.RouteStopStatus;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.SimplifiedRoutePlatform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Pseudo
@Mixin(targets = "com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleWrapper$StopsData", remap = false)
public abstract class JcmLimitedStopsMixin {
    @WrapOperation(method = "buildLimitedStopsData",
            at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;", ordinal = 0), require = 0)
    private static Iterator<SimplifiedRoute> fangsu$distinctRouteWindows(List<SimplifiedRoute> routes,
            Operation<Iterator<SimplifiedRoute>> original) {
        final var distinct = new ArrayList<SimplifiedRoute>();
        for (final var route : routes) {
            if (route.getPlatforms().isEmpty()) continue;
            if (distinct.isEmpty() || distinct.get(distinct.size() - 1).getId() != route.getId()) distinct.add(route);
        }
        return original.call(distinct);
    }

    @WrapOperation(method = "buildLimitedStopsData",
            slice = @Slice(from = @At(value = "INVOKE", target = "Lorg/mtr/core/data/SimplifiedRoute;getPlatforms()Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;")),
            at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;"), require = 0)
    private static Iterator<SimplifiedRoutePlatform> fangsu$onlyPassengerStops(List<SimplifiedRoutePlatform> platforms,
            Operation<Iterator<SimplifiedRoutePlatform>> original) {
        if (platforms.stream().allMatch(stop -> RouteStopStatus.of(stop) == RouteStopStatus.NORMAL)) {
            return original.call(platforms);
        }
        return platforms.stream().filter(stop -> RouteStopStatus.of(stop) == RouteStopStatus.NORMAL).iterator();
    }
}
