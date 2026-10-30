package com.fangsu.mixin;

import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mod.resource.OptimizedModelWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 只桥接 MTR 的模型包装构造器，不依赖 JCM 的模型访问器。 */
@Mixin(value = OptimizedModelWrapper.class, remap = false)
public interface LcdOptimizedModelWrapperInvoker {
    @Invoker("<init>")
    static OptimizedModelWrapper fangsu$create(OptimizedModel model) {
        throw new AssertionError();
    }
}
