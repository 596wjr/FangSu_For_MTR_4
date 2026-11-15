package com.fangsu.mixin;

import org.mtr.core.data.PathData;
import org.mtr.core.data.Siding;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = Siding.class, remap = false)
public interface ServiceRailSidingAccess {
    @Accessor("pathSidingToMainRoute")
    ObjectArrayList<PathData> fangsu$getOutboundPath();

    @Accessor("pathMainRouteToSiding")
    ObjectArrayList<PathData> fangsu$getInboundPath();
}
