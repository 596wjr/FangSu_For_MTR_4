package com.fangsu.mixin;

import com.fangsu.mtr.rail.ServiceRail;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.mtr.core.data.Rail;
import org.mtr.mod.data.RailType;
import org.mtr.mod.render.RenderRails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = RenderRails.class, remap = false)
public abstract class ServiceRailOverlayMixin {
    @WrapOperation(method = "lambda$render$3",
            at = @At(value = "INVOKE", target = "Lorg/mtr/mod/data/RailType;getRailColor(Lorg/mtr/core/data/Rail;)I"))
    private static int fangsu$serviceRailOverlay(Rail rail, Operation<Integer> original) {
        final var settings = ServiceRail.get(rail);
        return settings == null ? original.call(rail) : settings.kind().overlayColor;
    }
}
