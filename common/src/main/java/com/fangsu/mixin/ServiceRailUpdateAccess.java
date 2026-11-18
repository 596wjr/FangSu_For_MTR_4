package com.fangsu.mixin;

import org.mtr.core.data.Rail;
import org.mtr.core.data.Route;
import org.mtr.core.data.Station;
import org.mtr.core.generated.operation.UpdateDataRequestSchema;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = UpdateDataRequestSchema.class, remap = false)
public interface ServiceRailUpdateAccess {
    @Accessor("rails")
    ObjectArrayList<Rail> fangsu$getRails();

    @Accessor("routes")
    ObjectArrayList<Route> fangsu$getRoutes();

    @Accessor("stations")
    ObjectArrayList<Station> fangsu$getStations();
}
