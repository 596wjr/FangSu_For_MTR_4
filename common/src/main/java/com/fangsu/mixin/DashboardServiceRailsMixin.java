package com.fangsu.mixin;

import com.fangsu.mtr.rail.ServiceRail;
import org.mtr.core.data.Data;
import org.mtr.core.data.Rail;
import org.mtr.core.operation.ListDataResponse;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = ListDataResponse.class, remap = false)
public abstract class DashboardServiceRailsMixin {
    @Unique private static final String FANGSU_BYPASS_RAILS = "fangsu_bypass_rails";
    @Unique private List<Rail> fangsu$bypassRails;
    @Shadow @Final private Data data;

    @Inject(method = "<init>(Lorg/mtr/core/serializer/ReaderBase;Lorg/mtr/core/data/Data;)V", at = @At("RETURN"))
    private void fangsu$readBypassRails(ReaderBase reader, Data data, CallbackInfo ci) {
        fangsu$bypassRails = new ObjectArrayList<>();
        reader.iterateReaderArray(FANGSU_BYPASS_RAILS, fangsu$bypassRails::clear,
                child -> fangsu$bypassRails.add(new Rail(child)));
    }

    @Inject(method = "write", at = @At("HEAD"))
    private void fangsu$updateDashboardRails(CallbackInfo ci) {
        if (fangsu$bypassRails != null) {
            data.rails.removeIf(DashboardServiceRailsMixin::fangsu$isBypass);
            data.rails.addAll(fangsu$bypassRails);
        }
    }

    @Unique
    private static boolean fangsu$isBypass(Rail rail) {
        final var settings = ServiceRail.get(rail);
        return settings != null && settings.kind() == ServiceRail.Kind.BYPASS;
    }
}
