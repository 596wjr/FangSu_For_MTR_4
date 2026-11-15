package com.fangsu.mixin;

import com.fangsu.train.JcmStopLookup;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleWrapper;
import org.mtr.mod.data.VehicleExtension;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Pseudo
@Mixin(targets = "com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleWrapper", remap = false)
public abstract class JcmNextStopIndexMixin {
    @Shadow @Final protected VehicleExtension vehicleExtension;

    @Shadow public abstract List<VehicleWrapper.Stop> getStops();

    @Shadow public abstract int getNextStopIndex(List<VehicleWrapper.Stop> stops, double overrunTolerance);

    @Inject(method = "findNextStopIndex", at = @At("HEAD"), cancellable = true, require = 0)
    private void fangsu$findNextPassengerStop(double tolerance, double progress, List<VehicleWrapper.Stop> stops,
                                               CallbackInfoReturnable<Integer> cir) {
        if (!stops.isEmpty() && stops.get(0).distance < 0) {
            final int index = JcmStopLookup.limitedIndex(vehicleExtension.vehicleExtraData, progress, tolerance, stops);
            if (index >= 0) cir.setReturnValue(index);
        }
    }

    @Inject(method = "getThisRouteId", at = @At("RETURN"), cancellable = true, require = 0)
    private void fangsu$useCurrentRouteAtSharedStop(CallbackInfoReturnable<Long> cir) {
        final long routeId = vehicleExtension.vehicleExtraData.getThisRouteId();
        if (routeId == 0 || routeId == cir.getReturnValueJ()) return;
        final var stops = getStops();
        final int index = getNextStopIndex(stops, 0);
        if (index >= stops.size()) return;
        final var continuation = stops.get(index).roundUpRoute;
        if (continuation != null && continuation.route != null && continuation.route.getId() == routeId) {
            cir.setReturnValue(routeId);
        }
    }
}
