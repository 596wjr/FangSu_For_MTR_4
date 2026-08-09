package com.fangsu.mixin;

import org.mtr.mod.data.RailType;
import org.mtr.mod.item.ItemRailModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link ItemRailModifier} 的私有字段，供万向节点连接逻辑按连接器类型建轨。
 * <p>
 * {@code railType} 决定轨道种类与限速（含站台/侧线/折返），{@code isOneWay} 决定是否单向。
 */
@Mixin(value = ItemRailModifier.class, remap = false)
public interface ItemRailModifierAccessorMixin {

    @Accessor("railType")
    RailType getRailType();

    @Accessor("isOneWay")
    boolean isOneWay();
}
