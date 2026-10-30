package com.fangsu.mixin;

import org.mtr.mapping.render.tool.GlStateTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读取 MTR 的状态保护标记，避免 LCD 上传提前恢复外层已经捕获的 GL 状态。 */
@Mixin(value = GlStateTracker.class, remap = false)
public interface LcdGlStateTrackerAccessor {
    @Accessor("isStateProtected")
    static boolean fangsu$isStateProtected() {
        throw new AssertionError();
    }
}
