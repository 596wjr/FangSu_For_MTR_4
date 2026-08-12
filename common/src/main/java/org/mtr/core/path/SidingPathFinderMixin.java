package org.mtr.core.path;

import com.fangsu.mtr.PathAngleAccessor;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修复：万向节点轨道的寻路断连。
 * <p>
 * MTR core 的寻路（{@link SidingPathFinder#getConnections}）用
 * {@code node.angle == rail.getStartAngle(...)} 引用比较判断两段轨道能否衔接。
 * 原版轨道角度是 16 向 22.5° 枚举常量（{@link Angle}），同一档位即同一实例，比较成立；
 * 万向节点轨道使用任意精度角度（{@link com.fangsu.mtr.AngleExtra} 幻影实例，
 * {@code ordinal() == -1}，见 {@link com.fangsu.mixin.AngleMixin}），与相邻原版轨道的
 * 枚举常量（或不同角度的幻影实例）引用不相等，导致「万向节点轨道是唯一路径」时
 * 寻路失败（列车无法从普通轨道进入/离开万向节点轨道）。
 * <p>
 * 修复：注入替换 {@code getConnections}，角度匹配改用幻影兼容比较
 * （见 {@link #fangsu$anglesMatch}）：
 * <ul>
 *   <li>度数差 ≤2° → 视为衔接（几何连续，覆盖 {@code AngleExtra.PHANTOM_CACHE}
 *       float 缓存键的 1 ulp 误差）；</li>
 *   <li>否则快照到 16 向档位比较 → 模拟原版 22.5° 离散匹配语义（方向差不足半档
 *       视为平滑衔接，与原版节点间行为一致）。</li>
 * </ul>
 * 两个标准枚举实例仍按原版引用比较，不改变原版行为。
 * <p>
 * 本类放 {@code org.mtr.core.path} 包（与 core 分片）是为了访问 core 的
 * {@code protected} 嵌套类型 {@code PositionAndAngle} / {@code ConnectionDetails}，
 * 使注入方法签名与目标方法一致。
 */
@Mixin(value = SidingPathFinder.class, remap = false)
public abstract class SidingPathFinderMixin {

    @Shadow(remap = false)
    private Object2ObjectOpenHashMap<Position, Object2ObjectOpenHashMap<Position, Rail>> positionsToRail;

    @Shadow(remap = false)
    private Object2ObjectOpenHashMap<Position, Rail> runwaysInbound;

    @Shadow(remap = false)
    private ObjectOpenHashSet<Position> runwaysOutbound;

    @Shadow(remap = false)
    private TransportMode transportMode;

    @Inject(method = "getConnections", at = @At("HEAD"), remap = false, cancellable = true)
    private void fangsu$getConnections(long elapsedTime, SidingPathFinder.PositionAndAngle node, Long previousRouteId, CallbackInfoReturnable<ObjectArrayList<PathFinder.ConnectionDetails<SidingPathFinder.PositionAndAngle>>> cir) {
        final ObjectArrayList<PathFinder.ConnectionDetails<SidingPathFinder.PositionAndAngle>> connections = new ObjectArrayList<>();
        final Object2ObjectOpenHashMap<Position, Rail> railConnections = positionsToRail.get(fangsu$position(node));

        if (railConnections != null) {
            railConnections.forEach((position, rail) -> {
                final double speedLimit = rail.getSpeedLimitMetersPerMillisecond(fangsu$position(node));
                if (speedLimit > 0 && (fangsu$angle(node) == null || fangsu$anglesMatch(fangsu$angle(node), rail.getStartAngle(fangsu$position(node))) || rail.canTurnBack())) {
                    connections.add(new PathFinder.ConnectionDetails<>(PositionAndAngleMixin.fangsu$create(position, rail.getStartAngle(position).getOpposite()), Math.round(rail.railMath.getLength() / speedLimit), 0, 0));
                }
            });
        }

        if (transportMode == TransportMode.AIRPLANE && runwaysOutbound.contains(fangsu$position(node))) {
            runwaysInbound.forEach((position, rail) -> connections.add(new PathFinder.ConnectionDetails<>(PositionAndAngleMixin.fangsu$create(position, rail.getStartAngle(position)), 1, 0, 0)));
        }

        cir.setReturnValue(connections);
    }

    /**
     * 读取 {@code PositionAndAngle} 的 position（经 {@link PositionAndAngleMixin} 追加的访问器）。
     * 双重转型绕过编译期类型检查（接口由 mixin 在运行时实现）。
     */
    private static Position fangsu$position(SidingPathFinder.PositionAndAngle node) {
        return ((PathAngleAccessor) (Object) node).fangsu$position();
    }

    /** 读取 {@code PositionAndAngle} 的 angle（同上）。 */
    private static Angle fangsu$angle(SidingPathFinder.PositionAndAngle node) {
        return ((PathAngleAccessor) (Object) node).fangsu$angle();
    }

    /**
     * 幻影角度兼容的角度匹配（替代原版 {@code node.angle == rail.getStartAngle(...)} 的引用比较）。
     *
     * @param expected 期望的离开方向（前一段轨道进入角的 opposite）
     * @param actual   候选轨道在该节点处的出发方向
     * @return 是否可衔接
     */
    private static boolean fangsu$anglesMatch(Angle expected, Angle actual) {
        if (expected == actual) {
            return true;
        }
        // 两个标准枚举实例且不相等 → 原版语义不匹配（22.5° 档位已由快照保证）
        if (expected.ordinal() >= 0 && actual.ordinal() >= 0) {
            return false;
        }
        // 任一为幻影实例（万向节点任意精度角度，ordinal() == -1）：
        // 1) 度数差 ≤2° → 几何上连续（覆盖 PHANTOM_CACHE float 键的 1 ulp 误差）
        double diff = Math.toDegrees(expected.angleRadians - actual.angleRadians) % 360.0;
        if (diff > 180) {
            diff -= 360;
        }
        if (diff < -180) {
            diff += 360;
        }
        if (Math.abs(diff) <= 2.0) {
            return true;
        }
        // 2) 快照到同一 16 向档位 → 模拟原版 22.5° 离散匹配语义（方向差不足半档视为
        //    平滑衔接，使万向轨道与相邻轨道（含原版普通轨）的档内角度差不断连）
        return Angle.fromAngle((float) Math.toDegrees(expected.angleRadians)) == Angle.fromAngle((float) Math.toDegrees(actual.angleRadians));
    }
}
