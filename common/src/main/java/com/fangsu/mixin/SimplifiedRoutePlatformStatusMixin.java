package com.fangsu.mixin;

import com.fangsu.mtr.RouteStopStatus;
import org.mtr.core.generated.data.SimplifiedRoutePlatformSchema;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.WriterBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SimplifiedRoutePlatformSchema.class, remap = false)
public abstract class SimplifiedRoutePlatformStatusMixin implements RouteStopStatus.Holder {
    @Unique private static final String FANGSU_STOP_STATUS = "fangsu_stop_status";
    @Unique private RouteStopStatus fangsu$stopStatus;

    @Inject(method = "<init>(Lorg/mtr/core/serializer/ReaderBase;)V", at = @At("RETURN"))
    private void fangsu$readStatus(ReaderBase reader, CallbackInfo ci) {
        final String status = reader.getString(FANGSU_STOP_STATUS, "");
        fangsu$stopStatus = RouteStopStatus.NORMAL;
        if (!status.isEmpty()) {
            try {
                fangsu$stopStatus = RouteStopStatus.valueOf(status);
            } catch (IllegalArgumentException ignored) {
                // 未知状态按正常停靠处理。
            }
        }
    }

    @Inject(method = "serializeData", at = @At("RETURN"))
    private void fangsu$writeStatus(WriterBase writer, CallbackInfo ci) {
        if (fangsu$getStopStatus() != RouteStopStatus.NORMAL) {
            writer.writeString(FANGSU_STOP_STATUS, fangsu$stopStatus.name());
        }
    }

    @Override
    public RouteStopStatus fangsu$getStopStatus() {
        return fangsu$stopStatus == null ? RouteStopStatus.NORMAL : fangsu$stopStatus;
    }

    @Override
    public void fangsu$setStopStatus(RouteStopStatus status) {
        fangsu$stopStatus = status;
    }
}
