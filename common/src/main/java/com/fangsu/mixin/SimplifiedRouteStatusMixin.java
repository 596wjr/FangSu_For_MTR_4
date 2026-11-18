package com.fangsu.mixin;

import com.fangsu.mtr.RouteStopStatus;
import com.fangsu.mtr.ServiceStop;
import com.fangsu.mtr.StationOpening;
import org.mtr.core.data.Route;
import org.mtr.core.data.SimplifiedRoute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SimplifiedRoute.class, remap = false)
public abstract class SimplifiedRouteStatusMixin {
    @Inject(method = "<init>(Lorg/mtr/core/data/Route;)V", at = @At("RETURN"))
    private void fangsu$copyStopStatus(Route route, CallbackInfo ci) {
        final var simplified = ((SimplifiedRoute) (Object) this).getPlatforms();
        for (int index = 0; index < Math.min(simplified.size(), route.getRoutePlatforms().size()); index++) {
            final var stop = route.getRoutePlatforms().get(index);
            final var status = StationOpening.displayStatus(stop, stop.platform == null ? null : stop.platform.area);
            final var entry = simplified.get(index);
            RouteStopStatus.set(entry, status);
            ServiceStop.copyDisplayLink(stop, entry);
        }
    }
}
