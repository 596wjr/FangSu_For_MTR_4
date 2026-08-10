package com.fangsu.mixin;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.data.RailAction;
import org.mtr.mod.data.RailActionModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 MTR4 {@link RailActionModule} 的私有队列与广播方法，供混合构建器挂载构建动作。
 * <p>
 * MTR 运行时为官方映射（mojmap），字段/方法名即源码名，故 remap=false。
 * Forge 端不加载 mixin（mods.toml 无 mixin 声明），由 {@link com.fangsu.data.hybrid.HybridSliceAction#attach}
 * 的 instanceof 检测走反射回退。
 */
@Mixin(value = RailActionModule.class, remap = false)
public interface RailActionModuleAccessorMixin {

    @Accessor("railActions")
    ObjectArrayList<RailAction> getRailActions();

    @Invoker("broadcastUpdate")
    void invokeBroadcastUpdate();
}
