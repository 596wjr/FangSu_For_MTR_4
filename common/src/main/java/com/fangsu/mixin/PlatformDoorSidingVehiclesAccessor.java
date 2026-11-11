package com.fangsu.mixin;

import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 服务端在模拟线程枚举全部列车，包含无人乘坐的班次。 */
@Mixin(value=Siding.class,remap=false)
public interface PlatformDoorSidingVehiclesAccessor {
    @Accessor("vehicles") ObjectArraySet<Vehicle> fangsu$vehicles();
}
