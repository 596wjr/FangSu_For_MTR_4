package com.fangsu.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.data.Siding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Vehicle.class, remap = false)
public abstract class ServiceRailVehicleMixin {
    @Unique
    private long fangsu$parkingDwell;

    @Inject(method = "simulateStopped", at = @At("HEAD"))
    private void fangsu$resetParkingDwell(CallbackInfo ci) {
        fangsu$parkingDwell = 0;
    }

    @WrapOperation(method = "simulateStopped",
            at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/PathData;getDwellTime()J"))
    private long fangsu$readDwell(PathData segment, Operation<Long> original) {
        final long dwell = original.call(segment);
        fangsu$parkingDwell = dwell >= 1000 && segment.getSavedRailBaseId() == 0 ? dwell : 0;
        return dwell;
    }

    @WrapOperation(method = "simulateStopped", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(JJ)J", ordinal = 0))
    private long fangsu$waitFullDuration(long halfDuration, long beforeDoorsClose, Operation<Long> original) {
        return fangsu$parkingDwell > 0 ? fangsu$parkingDwell : original.call(halfDuration, beforeDoorsClose);
    }

    @WrapOperation(method = "simulateStopped",
            at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/VehicleExtraData;openDoors()V"))
    private void fangsu$keepDoorsClosed(VehicleExtraData vehicle, Operation<Void> original) {
        if (fangsu$parkingDwell == 0) {
            original.call(vehicle);
        }
    }

    @WrapOperation(method = "simulateStopped",
            at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Siding;getDelayedVehicleReduceDwellTimePercentage()I"))
    private int fangsu$keepParkingDuration(Siding siding, Operation<Integer> original) {
        return fangsu$parkingDwell > 0 ? 0 : original.call(siding);
    }

    @WrapOperation(method = "simulateStopped",
            at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Siding;getEarlyVehicleIncreaseDwellTime()Z"))
    private boolean fangsu$avoidExtendingParking(Siding siding, Operation<Boolean> original) {
        return fangsu$parkingDwell == 0 && original.call(siding);
    }
}
