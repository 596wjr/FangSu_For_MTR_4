package com.fangsu.mixin;

import org.mtr.core.data.PathData;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 TSC {@link SidingPathFinder} 的私有 {@code tick(long)} 方法，供
 * {@link com.fangsu.utils.DepotPathGenerationManager} 的独立工作线程逐段全速寻路。
 * <p>
 * MTR 运行时为官方映射（mojmap），方法名即源码名，故 remap=false。
 * {@code tick} 在类内唯一（javap 复核过）。注意 fastutil 运行期已搬迁为
 * {@code org.mtr.libraries.it.unimi.dsi.fastutil.*}，升级 TSC 时需 javap 复核。
 */
@Mixin(value = SidingPathFinder.class, remap = false)
public interface SidingPathFinderAccessorMixin {

    @Invoker("tick")
    ObjectArrayList<PathData> fangsu$invokeTick(long cruisingAltitude);
}
