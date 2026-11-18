package com.fangsu.mixin;

import com.fangsu.mappings.ComponentHelper;
import com.fangsu.mtr.StationOpening;
import org.mtr.core.data.Station;
import org.mtr.mapping.holder.ClickableWidget;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.mapper.CheckboxWidgetExtension;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.ScreenExtension;
import org.mtr.mod.screen.EditStationScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = EditStationScreen.class, remap = false)
public abstract class EditStationOpeningMixin {
    @Unique private Station fangsu$station;
    @Unique private boolean fangsu$deferred;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fangsu$readStation(Station station, ScreenExtension parent, CallbackInfo ci) {
        fangsu$station = station;
        fangsu$deferred = StationOpening.isDeferred(station);
    }

    @ModifyConstant(method = {"init2", "changeEditingExit"}, constant = @Constant(intValue = 70))
    private int fangsu$moveExitPanels(int y) {
        return y + 20;
    }

    @ModifyConstant(method = "render", constant = @Constant(intValue = 56))
    private int fangsu$moveExitHeadings(int y) {
        return y + 20;
    }

    @ModifyConstant(method = "render", constant = @Constant(doubleValue = 50))
    private double fangsu$moveExitDivider(double y) {
        return y + 20;
    }

    @Inject(method = "init2", at = @At("RETURN"))
    private void fangsu$addOpeningCheckbox(CallbackInfo ci) {
        final var label = new MutableText(ComponentHelper.translatable("ui.fangsu.station.deferred"));
        final var checkbox = new CheckboxWidgetExtension(6, 44, GraphicsHolder.getTextWidth(label) + 25, 20,
                label, true, checked -> fangsu$deferred = checked);
        checkbox.setChecked(fangsu$deferred);
        ((ScreenExtension) (Object) this).addChild(new ClickableWidget(checkbox));
    }

    @Inject(method = "saveData", at = @At("HEAD"))
    private void fangsu$saveOpening(CallbackInfo ci) {
        ((StationOpening) (Object) fangsu$station).fangsu$setDeferred(fangsu$deferred);
    }
}
