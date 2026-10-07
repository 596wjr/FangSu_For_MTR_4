package com.fangsu.mtr.rail;

/**
 * {@code org.mtr.core.data.RailMath} 的包围盒写入通道。
 * <p>
 * MTR 4.0.5 的 {@code RailMath.minX/minY/minZ/maxX/maxY/maxZ} 是 {@code public final long}，
 * 其值由<b>未平移的整数锚点</b>采样得到。节点平移后包围盒必须整体挪动，否则
 * {@code Rail.writePositionsToRailCache} / {@code closeTo} 的空间索引会把轨道登记到错误的格子里。
 * 该写入通道由 {@code RailMathAccessorMixin} 在 {@code RailMath} 上实现
 * （{@code @Shadow @Final @Mutable} + setter），{@code FangSuRailMath} 构造时用它覆盖包围盒。
 */
public interface RailMathBoundsAccessor {

    /** 用版本无关几何内核算出的包围盒覆盖 MTR 的包围盒。 */
    void fangsu$setBounds(long minX, long minY, long minZ, long maxX, long maxY, long maxZ);
}
