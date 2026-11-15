package com.fangsu.mixin;

import com.fangsu.mtr.rail.ServiceRail;
import org.mtr.core.data.Data;
import org.mtr.core.generated.operation.ListDataResponseSchema;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.simulation.Simulator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ListDataResponseSchema.class, remap = false)
public abstract class DashboardServiceRailsSchemaMixin {
    @Shadow protected abstract Data routesDataParameter();

    @Inject(method = "serializeData", at = @At("RETURN"))
    private void fangsu$writeBypassRails(WriterBase writer, CallbackInfo ci) {
        final Data data = routesDataParameter();
        if (data instanceof Simulator) {
            writer.writeDataset(data.rails.stream().filter(rail -> {
                final var settings = ServiceRail.get(rail);
                return settings != null && settings.kind() == ServiceRail.Kind.BYPASS;
            }).toList(), "fangsu_bypass_rails");
        }
    }
}
