package com.fangsu.mixin;

import com.fangsu.train.VehicleLcdRenderer;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.StoredMatrixTransformations;
import org.mtr.mod.resource.VehicleResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 参考 JCM 的提交位置，直接使用 MTR 已包含乘车偏移、摇晃和滚转的车体矩阵。 */
@Mixin(value = VehicleResource.class, remap = false)
public class VehicleResourceLcdMixin {
    @Inject(
            method = "queue(Lorg/mtr/mod/render/StoredMatrixTransformations;Lorg/mtr/mod/data/VehicleExtension;IIIZ)V",
            at = @At("TAIL"), remap = false
    )
    private void fangsu$queueLcd(StoredMatrixTransformations transformations, VehicleExtension vehicle,
                                 int carNumber, int totalCars, int light, boolean noOpenDoorways,
                                 CallbackInfo ci) {
        VehicleLcdRenderer.renderCar(vehicle, carNumber, transformations, light);
    }
}
