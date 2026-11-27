package com.fangsu.mixin;

import com.fangsu.render.MtrTranslucentPass;
import com.fangsu.train.parts.TrainPartRenderAdapterMtr4;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.mtr.mapping.holder.RenderLayer;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.resource.OptimizedRendererWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = MainRenderer.class, remap = false)
public abstract class MtrMainTranslucencyMixin {
    @WrapOperation(method = "render(Lorg/mtr/mapping/mapper/GraphicsHolder;Lorg/mtr/mapping/holder/Vector3d;)V",
            at = @At(value = "INVOKE", target = "Lorg/mtr/mod/render/RenderVehicles;render(JLorg/mtr/mapping/holder/Vector3d;)V"))
    private static void fangsu$renderTrainParts(long elapsed, Vector3d cameraShake, Operation<Void> original,
                                                 @Local(argsOnly = true) GraphicsHolder graphics,
                                                 @Local(argsOnly = true) Vector3d cameraOffset) {
        TrainPartRenderAdapterMtr4.renderVehicles(graphics, cameraOffset, () -> original.call(elapsed, cameraShake));
    }

    @WrapOperation(method = "lambda$render$3", at = @At(value = "INVOKE",
            target = "Lorg/mtr/mapping/mapper/GraphicsHolder;createVertexConsumer(Lorg/mtr/mapping/holder/RenderLayer;)V"))
    private static void fangsu$queueTranslucentLayer(GraphicsHolder graphics, RenderLayer layer, Operation<Void> original,
                                                    @Local(argsOnly = true) QueuedRenderLayer stage) {
        if (MtrTranslucentPass.isCollecting() && (stage == QueuedRenderLayer.INTERIOR_TRANSLUCENT
                || stage == QueuedRenderLayer.EXTERIOR_TRANSLUCENT || stage == QueuedRenderLayer.LIGHT_TRANSLUCENT)) {
            ((MtrGraphicsConsumerAccessor) (Object) graphics).fangsu$setVertexConsumer(MtrTranslucentPass.getBuffer(layer.data));
        } else {
            original.call(graphics, layer);
        }
    }

    @WrapOperation(method = "render(Lorg/mtr/mapping/mapper/GraphicsHolder;Lorg/mtr/mapping/holder/Vector3d;)V",
            at = @At(value = "INVOKE", target = "Lorg/mtr/mod/resource/OptimizedRendererWrapper;render(Z)V"))
    private static void fangsu$collectOptimized(OptimizedRendererWrapper renderer, boolean translucent, Operation<Void> original) {
        if (translucent && MtrTranslucentPass.isCollecting()) {
            MtrTranslucentPass.collectOptimized(() -> original.call(renderer, true));
        } else {
            original.call(renderer, translucent);
        }
    }
}
