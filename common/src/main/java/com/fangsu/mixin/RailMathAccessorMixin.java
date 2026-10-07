package com.fangsu.mixin;

import com.fangsu.mtr.rail.RailMathBoundsAccessor;
import org.mtr.core.data.RailMath;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 给 {@link RailMath} 打开包围盒写入通道。
 * <p>
 * MTR 4.0.5 的包围盒是 {@code public final long minX/minY/minZ/maxX/maxY/maxZ}，
 * 由构造器用<b>未平移的整数锚点</b>采样得出。节点平移后 {@link com.fangsu.mtr.rail.FangSuRailMath}
 * 必须把它们整体改成内核采样值，否则 {@code Rail.writePositionsToRailCache} /
 * {@code Rail.closeTo} 会把轨道登记到原来的格子上（= 平移后轨道在空间索引里"消失"）。
 * <p>
 * 这里用 {@code @Shadow @Final @Mutable} 让字段可写，并把 {@code @Unique} 的 setter
 * 暴露成 {@link RailMathBoundsAccessor}。仅此一个用途，不做别的改动。
 */
@Mixin(value = RailMath.class, remap = false)
public abstract class RailMathAccessorMixin implements RailMathBoundsAccessor {

    @Shadow(remap = false) @Final @Mutable
    public long minX;

    @Shadow(remap = false) @Final @Mutable
    public long minY;

    @Shadow(remap = false) @Final @Mutable
    public long minZ;

    @Shadow(remap = false) @Final @Mutable
    public long maxX;

    @Shadow(remap = false) @Final @Mutable
    public long maxY;

    @Shadow(remap = false) @Final @Mutable
    public long maxZ;

    @Override
    public void fangsu$setBounds(long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }
}
