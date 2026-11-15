package com.fangsu.mixin;

import org.mtr.core.generated.data.RoutePlatformDataSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = RoutePlatformDataSchema.class, remap = false)
public interface RoutePlatformDataAccess {
    @Accessor("platformId")
    long fangsu$getPlatformId();
}
