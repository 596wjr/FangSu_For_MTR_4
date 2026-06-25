package com.fangsu.mixin;

import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Data.class)
public class DataMixin {
    @Final
    @Shadow(remap = false)
    public Object2ObjectOpenHashMap<Position, Object2ObjectOpenHashMap<Position, Rail>> positionsToRail;
}
