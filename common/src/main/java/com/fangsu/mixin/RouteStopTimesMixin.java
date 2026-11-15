package com.fangsu.mixin;

import com.fangsu.mtr.RouteStopStatus;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.mtr.core.data.Route;
import org.mtr.core.data.Siding;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(value = Siding.class, remap = false)
public abstract class RouteStopTimesMixin {
    @WrapOperation(
            method = "generatePathDistancesAndTimeSegments",
            slice = @Slice(from = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Route;getDestination(I)Ljava/lang/String;")),
            at = @At(value = "INVOKE", target = "Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;add(Ljava/lang/Object;)Z", ordinal = 0)
    )
    private boolean fangsu$recordOnlyPassengerStops(ObjectArrayList<Object> stops, Object value, Operation<Boolean> original,
                                                   @Local Route route, @Local(index = 11) int index) {
        return RouteStopStatus.of(route.getRoutePlatforms().get(index)) == RouteStopStatus.NORMAL && original.call(stops, value);
    }
}
