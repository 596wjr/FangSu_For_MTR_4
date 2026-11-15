package com.fangsu.mixin;

import com.fangsu.mtr.RouteStopStatus;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleDataCache;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleWrapper;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.SimplifiedRoutePlatform;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleDataCache", remap = false)
public abstract class JcmStopsDataCacheMixin {
    @WrapOperation(method = "buildVehicleStopsData", at = @At(value = "INVOKE",
            target = "Lorg/mtr/core/data/SimplifiedRoute;getPlatforms()Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;"),
            require = 0)
    private static ObjectArrayList<SimplifiedRoutePlatform> fangsu$alignPassengerInterchanges(SimplifiedRoute route,
            Operation<ObjectArrayList<SimplifiedRoutePlatform>> original) {
        final var platforms = original.call(route);
        if (platforms.stream().allMatch(stop -> RouteStopStatus.of(stop) == RouteStopStatus.NORMAL)) return platforms;
        final var passengerStops = new ObjectArrayList<SimplifiedRoutePlatform>();
        for (final var stop : platforms) {
            if (RouteStopStatus.of(stop) == RouteStopStatus.NORMAL) passengerStops.add(stop);
        }
        return passengerStops;
    }

    @ModifyExpressionValue(method = "buildVehicleStopsData", at = @At(value = "FIELD",
            target = "Lcom/lx862/mtrscripting/mod/impl/mtr/vehicle/VehicleDataCache$SimplifiedStop;platformId:J",
            opcode = org.objectweb.asm.Opcodes.GETFIELD, ordinal = 1), require = 0)
    private static long fangsu$mergeOnlySharedStops(long platformId, @Local(index = 4) long previousPlatformId,
            @Local VehicleDataCache.SimplifiedStop stop, @Local VehicleWrapper.StopsData data) {
        // 跳站后同一站台可能连续出现两次；仅在停车里程也相同时才是同一次停车。
        if (platformId == previousPlatformId && (data.allStops.isEmpty()
                || data.allStops.get(data.allStops.size() - 1).distance != stop.distance)) {
            return platformId ^ Long.MIN_VALUE;
        }
        return platformId;
    }

    @ModifyReturnValue(method = "buildVehicleStopsData", at = @At("RETURN"), require = 0)
    private static VehicleWrapper.StopsData fangsu$fallbackFromEmptyData(VehicleWrapper.StopsData data) {
        return data != null && data.allStops.isEmpty() ? null : data;
    }
}
