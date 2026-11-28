package com.fangsu.mixin;

import com.fangsu.train.selection.TrainFormationPresets;
import org.mtr.mod.client.CustomResourceLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CustomResourceLoader.class, remap = false)
public abstract class TrainFormationReloadMixin {
    @Inject(method = "reload()V", at = @At("TAIL"))
    private static void fangsu$loadTrainFormations(CallbackInfo ci) {
        TrainFormationPresets.reload();
    }
}
