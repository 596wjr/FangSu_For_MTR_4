package com.fangsu.mixin;

import com.fangsu.mtr.RouteStopStatus;
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
            final var status = RouteStopStatus.of(route.getRoutePlatforms().get(index));
            ((RouteStopStatus.Holder) (Object) simplified.get(index)).fangsu$setStopStatus(status);
        }
    }
}
