package com.fangsu.mixin;

import org.mtr.core.Main;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link Main#simulators}（各维度 Simulator 列表），
 * 供隐藏线路请求（{@code HiddenRoutesPackets}）按维度匹配定位 Simulator。
 * 与 JCM 的 com.lx862.jcm.mixin.modded.tsc.MainAccessorMixin 同款。
 */
@Mixin(value = Main.class, remap = false)
public interface MainAccessorMixin {

	@Accessor("simulators")
	ObjectImmutableList<Simulator> getSimulators();
}
