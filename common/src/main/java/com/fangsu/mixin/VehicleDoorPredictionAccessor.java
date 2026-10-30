package com.fangsu.mixin;

import org.mtr.core.generated.data.VehicleSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 只读当前行驶里程，用于定位前方真正有停站时间的站台。 */
@Mixin(value = VehicleSchema.class, remap = false)
public interface VehicleDoorPredictionAccessor {
    @Accessor("railProgress")
    double fangsu$getRailProgress();
}
