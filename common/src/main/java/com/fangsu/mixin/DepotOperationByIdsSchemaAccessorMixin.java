package com.fangsu.mixin;

import org.mtr.core.generated.operation.DepotOperationByIdsSchema;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@code depotIds} 字段：声明在 TSC schema 生成类
 * {@link DepotOperationByIdsSchema}（{@code depotOperationByIds.json} 的 long 数组 →
 * {@code protected final LongArrayList depotIds}，无手写 getter），
 * {@code DepotOperationByIds} 只继承不声明。
 * <p>
 * 注意：@Accessor 必须 target 到**声明字段的类**——Mixin 注解处理器（编译期）
 * 的字段查找不沿继承链（运行时 @Accessor 支持继承字段，但编译期直接报
 * "Could not locate @Accessor target"，本项目实测）。schema 类被注入接口后，
 * 子类 {@code DepotOperationByIds} 实例的 instanceof 与接口方法分派均沿继承链
 * 落到父类实现，调用方直接 cast 即可。fastutil 运行期已搬迁为
 * {@code org.mtr.libraries.*}。
 */
@Mixin(value = DepotOperationByIdsSchema.class, remap = false)
public interface DepotOperationByIdsSchemaAccessorMixin {

    @Accessor("depotIds")
    LongArrayList getDepotIds();
}
