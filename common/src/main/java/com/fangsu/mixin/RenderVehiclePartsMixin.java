package com.fangsu.mixin;

import com.fangsu.train.parts.TrainPartRenderAdapterMtr4;
import com.llamalad7.mixinextras.sugar.Local;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.PositionAndRotation;
import org.mtr.mod.render.RenderVehicles;
import org.mtr.mod.render.StoredMatrixTransformations;
import org.mtr.mod.resource.VehicleResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderVehicles.class, remap = false)
public abstract class RenderVehiclePartsMixin {
    private static final String DRAW_MODE = "Lorg/mtr/mapping/mapper/OptimizedRenderer;hasOptimizedRendering()Z";

    @Inject(method = "lambda$render$5", at = @At(value = "INVOKE", target = DRAW_MODE), cancellable = true, require = 0)
    private static void fangsu$renderBogie(Vector3d offset, Double rotation, PositionAndRotation ridingCar,
                                          Vector3d cameraShake, VehicleResource resource, VehicleExtension vehicle,
                                          PositionAndRotation carPose, int car, int[] display, boolean preview, int slot,
                                          PositionAndRotation bogiePose, CallbackInfo callback,
                                          @Local StoredMatrixTransformations transform) {
        if (TrainPartRenderAdapterMtr4.bogie(vehicle, resource.getId(), car, slot, transform, carPose.light)) callback.cancel();
    }

    @Inject(method = "lambda$render$14", at = @At(value = "INVOKE", target = DRAW_MODE), require = 0)
    private static void fangsu$renderPantographs(CallbackInfo callback,
                                                @Local(argsOnly = true) VehicleExtension vehicle,
                                                @Local(index = 6) int car,
                                                @Local VehicleResource resource,
                                                @Local(index = 21) PositionAndRotation carPose,
                                                @Local StoredMatrixTransformations transform) {
        TrainPartRenderAdapterMtr4.pantographs(vehicle, resource.getId(), car, transform, carPose.light);
    }
}
