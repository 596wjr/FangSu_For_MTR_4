package com.fangsu.mixin;

import com.fangsu.mtr.StationOpening;
import org.mtr.core.generated.data.StationSchema;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.WriterBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = StationSchema.class, remap = false)
public abstract class StationOpeningMixin implements StationOpening {
    @Unique private volatile boolean fangsu$deferred;

    @Override
    public boolean fangsu$isDeferred() {
        return fangsu$deferred;
    }

    @Override
    public void fangsu$setDeferred(boolean deferred) {
        fangsu$deferred = deferred;
    }

    @Inject(method = "updateData", at = @At("RETURN"))
    private void fangsu$readOpening(ReaderBase reader, CallbackInfo ci) {
        fangsu$deferred = reader.getBoolean(StationOpening.KEY, false);
    }

    @Inject(method = "serializeData", at = @At("RETURN"))
    private void fangsu$writeOpening(WriterBase writer, CallbackInfo ci) {
        if (fangsu$deferred) writer.writeBoolean(StationOpening.KEY, true);
    }
}
