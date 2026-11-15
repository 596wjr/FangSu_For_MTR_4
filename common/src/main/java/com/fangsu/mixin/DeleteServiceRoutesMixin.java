package com.fangsu.mixin;

import com.fangsu.mtr.RouteServices;
import org.mtr.core.data.Depot;
import org.mtr.core.operation.DeleteDataRequest;
import org.mtr.core.operation.DeleteDataResponse;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = DeleteDataRequest.class, remap = false)
public abstract class DeleteServiceRoutesMixin {
    @Inject(method = "delete", at = @At("HEAD"))
    private void fangsu$deleteDependentRoutes(Simulator simulator, CallbackInfoReturnable<DeleteDataResponse> cir) {
        final var ids = ((DeleteServiceRouteIdsAccess) (Object) this).fangsu$getRouteIds();
        if (ids.isEmpty()) {
            return;
        }
        final var parents = new LongArrayList(ids);
        for (final var route : simulator.routes) {
            final var relation = RouteServices.relation(route);
            if (relation != null && parents.contains(relation.parentRoute()) && !ids.contains(route.getId())) {
                ids.add(route.getId());
            }
        }

        // 在原版同步移除车厂引用前记录受影响的车厂，删除提交后再生成路径。
        final var depots = new ObjectArrayList<Depot>();
        for (final var depot : simulator.depots) {
            if (depot.getRouteIds().stream().anyMatch(ids::contains)) {
                depots.add(depot);
            }
        }
        if (!depots.isEmpty()) {
            simulator.run(() -> Depot.generateDepots(simulator, depots));
        }
    }
}
