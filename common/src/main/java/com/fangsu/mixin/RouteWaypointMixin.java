package com.fangsu.mixin;

import com.fangsu.mtr.RouteStopStatus;
import com.fangsu.mtr.ServiceStop;
import org.mtr.core.data.Platform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = Platform.class, remap = false)
public abstract class RouteWaypointMixin implements RouteStopStatus.Holder, ServiceStop.Holder {
    @Unique private ServiceStop fangsu$serviceStop;

    @Override
    public ServiceStop fangsu$getServiceStop() {
        return fangsu$serviceStop;
    }

    @Override
    public void fangsu$setServiceStop(ServiceStop metadata) {
        fangsu$serviceStop = metadata;
    }
    @Unique private RouteStopStatus fangsu$stopStatus;

    @Override
    public RouteStopStatus fangsu$getStopStatus() {
        return fangsu$stopStatus == null ? RouteStopStatus.NORMAL : fangsu$stopStatus;
    }

    @Override
    public void fangsu$setStopStatus(RouteStopStatus status) {
        fangsu$stopStatus = status;
    }
}
