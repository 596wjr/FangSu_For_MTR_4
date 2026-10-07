package com.fangsu.mtr.rail;

import com.fangsu.mappings.rail.RailPoseExtra;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.tool.Angle;

/**
 * 轨道「附加姿态」存取接口（MTR4 适配层）。
 * <p>
 * 与 {@link com.fangsu.mtr.AngleExtra} 同一套路：MTR 的 {@code org.mtr.core.data.Rail} 是
 * {@code public final class}，且 {@code RailSchema} 的字段都是 {@code protected final}，
 * 无法继承或用现有字段承载 FangSu 的附加数据；因此附加姿态由 mixin 以 {@code @Unique} 字段
 * 挂在 {@code RailSchema} 上，调用方通过本接口强转取值。
 * <p>
 * <b>实现分工（重要）</b>：
 * <ul>
 *   <li>{@code getFangSuPose} / {@code setFangSuPose} —— 由 {@code RailSchemaMixin} 实现
 *       （数据住在 {@code RailSchema} 上，序列化读写在 {@code RailSchema} 的构造器 /
 *       {@code serializeData} 里，必须与数据同层）；</li>
 *   <li>{@code rebuildRailMath} —— 由 {@code RailMixin} 在 {@code Rail} 上实现
 *       （{@code railMath} 字段属于 {@code Rail}）。</li>
 * </ul>
 * 因为两个 mixin 分别打在父类与子类上，接口里 {@code rebuildRailMath} 只能给一个
 * <b>默认空实现</b>：{@code RailSchemaMixin} 那边必须满足接口契约，而真正的实现由
 * {@code RailMixin} 加到 {@code Rail} 上（子类方法覆盖接口默认方法，运行期分派到真实现）。
 */
public interface RailPoseExtraHolder {

    /** 当前附加姿态；从未设置过时返回 {@link RailPoseExtra#DEFAULT}（绝不为 null）。 */
    RailPoseExtra getFangSuPose();

    /**
     * 仅写入姿态数据，<b>不</b>重建几何。
     * 写入方紧接着必须调用 {@link #rebuildRailMath()}（或直接用 {@link #apply(Rail, RailPoseExtra)}）。
     */
    void setFangSuPose(RailPoseExtra pose);

    /**
     * 依据当前姿态重建 {@code railMath}。
     * <p>
     * 由 {@code RailMixin} 在 {@code org.mtr.core.data.Rail} 上实现；默认空实现只是为了满足
     * {@code RailSchemaMixin} 的编译期接口契约（见类注释）。
     * <p>
     * 契约：姿态为默认时必须安装 MTR <b>原生</b> {@code RailMath} 实例（不是子类），
     * 保证原版轨道几何逐位一致。
     */
    default void rebuildRailMath() {
    }

    // ==================== 重建几何所需的端点信息 ====================
    // 这六个目标成员（position1/position2/angle1/angle2/shape/verticalRadius）声明在
    // org.mtr.core.generated.data.RailSchema 上，**不在** org.mtr.core.data.Rail 上
    // （已用 javap 对真实 4.0.5 jar 核实：Rail 自身只多出 railMath 等字段）。
    // 因此对应的 @Shadow 必须打进目标为 RailSchema 的 mixin（RailSchemaMixin）：
    // 打在 RailMixin 里会被 Mixin 注解处理器报
    // "Cannot find target for @Shadow field in org.mtr.core.data.Rail"，运行期也有解析不到的风险。
    // 这里用接口把它们暴露给 RailMixin（railMath 字段属于 Rail，只能在 RailMixin 里重建几何）。

    /** @return {@code Rail.position1}（MTR 的端点 1，与 {@code reversePositions} 无关）。 */
    Position fangsu$getPosition1();

    /** @return {@code Rail.position2}。 */
    Position fangsu$getPosition2();

    /** @return {@code Rail.angle1}（端点 1 的进入/离开角度）。 */
    Angle fangsu$getAngle1();

    /** @return {@code Rail.angle2}。 */
    Angle fangsu$getAngle2();

    /** @return {@code Rail.shape}（QUADRATIC / TWO_RADII / CABLE）。 */
    Rail.Shape fangsu$getShape();

    /** @return {@code Rail.verticalRadius}（未经 {@code min(., maxVerticalRadius)} 收缩的原始值）。 */
    double fangsu$getVerticalRadius();

    /** 便捷方法：写入姿态并立即重建几何。 */
    static void apply(Rail rail, RailPoseExtra pose) {
        if (rail == null) {
            return;
        }
        final RailPoseExtraHolder holder = (RailPoseExtraHolder) (Object) rail;
        holder.setFangSuPose(pose);
        holder.rebuildRailMath();
    }

    /** 读取轨道的附加姿态（{@code null} 轨道返回默认姿态）。 */
    static RailPoseExtra peek(Rail rail) {
        if (rail == null) {
            return RailPoseExtra.DEFAULT;
        }
        return ((RailPoseExtraHolder) (Object) rail).getFangSuPose();
    }
}
