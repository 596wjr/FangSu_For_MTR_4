package com.fangsu.mixin;

import org.mtr.core.generated.operation.DeleteDataRequestSchema;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = DeleteDataRequestSchema.class, remap = false)
public interface DeleteServiceRouteIdsAccess {
    @Accessor("routeIds")
    LongArrayList fangsu$getRouteIds();
}
