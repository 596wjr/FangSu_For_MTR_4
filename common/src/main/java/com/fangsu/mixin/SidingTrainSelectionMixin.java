package com.fangsu.mixin;

import com.fangsu.ui.train.TrainSelectionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Siding;
import org.mtr.core.data.TransportMode;
import org.mtr.mapping.holder.ButtonWidget;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.mapper.ScreenExtension;
import org.mtr.mod.screen.SavedRailScreenBase;
import org.mtr.mod.screen.SidingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SidingScreen.class, remap = false)
public abstract class SidingTrainSelectionMixin extends SavedRailScreenBase<Siding, Depot> {
    protected SidingTrainSelectionMixin(Siding siding, TransportMode mode, ScreenExtension parent, MutableText... labels) {
        super(siding, mode, parent, labels);
    }

    @Inject(method = "lambda$new$0(Lorg/mtr/mapping/holder/ButtonWidget;)V", at = @At("HEAD"), cancellable = true)
    private void fangsu$openTrainSelection(ButtonWidget button, CallbackInfo ci) {
        if (savedRailBase.getTransportMode() == TransportMode.TRAIN) {
            Minecraft.getInstance().setScreen(new TrainSelectionScreen(savedRailBase, (Screen) (Object) this));
            ci.cancel();
        }
    }
}
