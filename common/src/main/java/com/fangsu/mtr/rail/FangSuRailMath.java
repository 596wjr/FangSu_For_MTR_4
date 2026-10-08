package com.fangsu.mtr.rail;

import com.fangsu.mappings.rail.RailGeometryCore;
import com.fangsu.mappings.rail.RailPoseExtra;
import com.fangsu.mappings.rail.RailRollProfile;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleDoubleImmutablePair;

/**
 * 带 FangSu 附加姿态的 {@link RailMath} 子类（MTR 4.0.5 适配层）。
 * <p>
 * {@code org.mtr.core.data.RailMath} 在 4.0.5 里<b>不是 final</b>，因此可以直接继承。
 * 所有几何量都委托给版本无关内核 {@link RailGeometryCore}（纯数学，与 MTR 4.0.5 逐位等价，
 * 见 {@code deliverables/fangsu-railmath-parity}）。
 * <p>
 * <b>为什么必须整体替换而不是增量修补</b>：MTR 4.0.5 的 {@code RailMath} 用整数
 * {@code Position} 计算 h/k/r/t，锚点被锁死在整格上；节点平移是亚格（double）量，
 * 只有把 h/k/r/t 全部换成双精度重算，轨面、车辆路径与包围盒才会一起跟着走。
 * <p>
 * <b>端点顺序</b>：MTR 的 {@code Rail} 构造器在 {@code position1.compareTo(position2) > 0}
 * （{@code reversePositions}）时，用 {@code (position2, angle2, position1, angle1)} 的顺序
 * 构造 {@code RailMath}，因此 {@code railMath} 的参数 0 端点不一定是 {@code Rail.position1}。
 * 本类通过 {@code firstIsPosition1} 把 {@code RailPoseExtra} 的两端偏移、俯仰与滚转剖面映射到
 * 「railMath 参数顺序」上，调用方（{@code RailMixin}）负责传入与 MTR 完全一致的端点顺序。
 * <p>
 * <b>未覆盖的方法</b>：{@code RailMath.isValid()} 是包级私有（{@code boolean isValid()}），
 * 无法从 {@code com.fangsu.*} 覆盖。本类构造时调用了 {@code super(...)}，因此父类
 * {@code isValid()} 反映的是<b>未平移</b>几何的退化判定；亚格平移不改变退化性
 * （h/k/r/t 的零点位置与「两角是否平行、是否共线」判定无关），所以直接复用父类结果是安全的。
 * 俯仰 / 滚转步（P3+）若要改变退化判定，需另行处理。
 */
public class FangSuRailMath extends RailMath {

    /**
     * 判定「某一段几何退化（没有两半径接缝）」的长度阈值，单位格。
     * 取 1e-6 而非 0：内核的段长是两个 t 边界之差，浮点误差下可能得到 1e-15 这种"名义非零"的
     * 退化段，那样算出的 {@code count1 / length} 会退化成 0 或 1，中间控制点被挤到端点上。
     */
    private static final double DEGENERATE_SEGMENT_LENGTH = 1.0E-6D;

    /** 版本无关几何内核。 */
    private final RailGeometryCore core;
    /** 形状（父类字段私有，这里单独留一份用于 {@link #getShape()}）。 */
    private final Rail.Shape shape;
    /** 写入时使用的附加姿态（已按 railMath 参数顺序取值，仅作诊断/后续渲染扩展用）。 */
    private final RailPoseExtra pose;
    /**
     * railMath 参数 0 端是否就是 {@code Rail.position1}（构造时由调用方按 MTR 的端点顺序给出）。
     * <p>
     * 只给 {@link #middleBreakpointFraction(RailMath)} 用：接缝位置是在「railMath 参数空间」里算出来的，
     * 而这个实例是唯一同时知道几何与端点顺序的地方，换算成 {@code position1 → position2} 空间要靠它。
     */
    private final boolean firstIsPosition1;

    /**
     * 一次性日志标记：主钩子（{@code RailMixin} 的 {@code NEW RailMath} 重定向）是否已经
     * 打印过"生效"日志。
     * <p>
     * 放在这个普通类而不是 mixin 里：mixin 的 {@code @Unique} 静态字段初始化器依赖 Mixin
     * 的字段初始化器搬运机制，行为随版本而变；普通类字段没有任何不确定性。
     * 并发上只可能重复打印一次日志，无害。
     */
    private static boolean firstInstallLogged = false;

    /**
     * 第一次调用返回 {@code true}（调用方据此打印一次 INFO），之后返回 {@code false}。
     * 用于在不刷屏的前提下向日志证明"几何主钩子确实生效了"。
     */
    public static boolean markFirstInstallLogged() {
        if (firstInstallLogged) {
            return false;
        }
        firstInstallLogged = true;
        return true;
    }

    /**
     * @param firstPosition      railMath 参数 0 端点（MTR 给的顺序，可能是 position2）
     * @param secondPosition     railMath 参数 length 端点
     * @param verticalRadius     竖曲线半径（未做 min(., max) 收缩，内核会自行收缩，与 4.0.5 一致）
     * @param pose               轨道的附加姿态（<b>语义顺序</b>：{@code offsetX1} 属于 {@code Rail.position1}）
     * @param firstIsPosition1   {@code firstPosition} 是否就是 {@code Rail.position1}
     */
    public FangSuRailMath(
            Position firstPosition, Angle firstAngle,
            Position secondPosition, Angle secondAngle,
            Rail.Shape shape, double verticalRadius,
            RailPoseExtra pose, boolean firstIsPosition1
    ) {
        super(firstPosition, firstAngle, secondPosition, secondAngle, shape, verticalRadius);
        this.shape = shape;
        this.pose = pose;
        this.firstIsPosition1 = firstIsPosition1;

        // 端点偏移：按 firstIsPosition1 把语义两端的偏移映射到 railMath 的参数顺序
        final double offsetX1 = firstIsPosition1 ? pose.offsetX1 : pose.offsetX2;
        final double offsetY1 = firstIsPosition1 ? pose.offsetY1 : pose.offsetY2;
        final double offsetZ1 = firstIsPosition1 ? pose.offsetZ1 : pose.offsetZ2;
        final double offsetX2 = firstIsPosition1 ? pose.offsetX2 : pose.offsetX1;
        final double offsetY2 = firstIsPosition1 ? pose.offsetY2 : pose.offsetY1;
        final double offsetZ2 = firstIsPosition1 ? pose.offsetZ2 : pose.offsetZ1;
        final double pitch1 = Math.toRadians(firstIsPosition1 ? pose.pitch1Degrees : pose.pitch2Degrees);
        final double pitch2 = Math.toRadians(firstIsPosition1 ? pose.pitch2Degrees : pose.pitch1Degrees);
        // 滚转剖面：必须与 pitch1/pitch2 走同一套端点映射，取法与理由集中在
        // rollProfileFor（含「未编辑逐轨道超高时与引入本特性之前逐位相同」的不变式）。
        // 早先这里直接写 pose.toRollProfile()，会把 roll1Degrees 永远钉在参数 0 端：
        // 在 position1.compareTo(position2) > 0（firstIsPosition1 == false）的轨道上两端滚转被接反。
        final RailRollProfile rollProfile = rollProfileFor(pose, firstIsPosition1);

        // 锚点 = 方块坐标 + 节点偏移；+0.5 的格心平移留在内核的位置公式里，
        // 所以偏移为 0 时内核与 4.0.5 逐位一致（内核注释与 parity harness 都基于这一点）。
        final double[] anchor1 = {
                firstPosition.getX() + offsetX1,
                firstPosition.getY() + offsetY1,
                firstPosition.getZ() + offsetZ1
        };
        final double[] anchor2 = {
                secondPosition.getX() + offsetX2,
                secondPosition.getY() + offsetY2,
                secondPosition.getZ() + offsetZ2
        };

        // 角度取 angleRadians（double 精度）；幻影角度（AngleMixin）也把原始弧度存在该字段里，
        // 因此任意角度与 RailMath 的枚举角度走同一路径。
        this.core = new RailGeometryCore(
                anchor1, anchor2,
                firstAngle.angleRadians, secondAngle.angleRadians,
                shapeMode(shape), verticalRadius,
                pitch1, pitch2,
                rollProfile, pose.halfGauge
        );

        // 包围盒：MTR 的 minX..maxZ 是用未平移的整数锚点采样出来的，平移后必须整体取内核结果，
        // 否则轨道会被登记到错误的区块里（Rail.writePositionsToRailCache 用的就是这些字段）。
        ((RailMathBoundsAccessor) (Object) this).fangsu$setBounds(
                core.minX, core.minY, core.minZ, core.maxX, core.maxY, core.maxZ
        );
    }

    /** MTR {@code Rail.Shape} → 内核形状常量。 */
    public static int shapeMode(Rail.Shape shape) {
        if (shape == Rail.Shape.TWO_RADII) {
            return RailGeometryCore.SHAPE_TWO_RADII;
        }
        if (shape == Rail.Shape.CABLE) {
            return RailGeometryCore.SHAPE_CABLE;
        }
        return RailGeometryCore.SHAPE_QUADRATIC;
    }

    /** 本实例安装时使用的附加姿态（语义顺序，仅供诊断）。 */
    public RailPoseExtra getFangSuPose() {
        return pose;
    }

    /** 版本无关几何内核（供渲染/测试读取横断面、滚转等扩展量）。 */
    public RailGeometryCore getGeometryCore() {
        return core;
    }

    /**
     * 由附加姿态与端点顺序选出实际生效的滚转剖面（几何内核唯一消费的滚转输入）。
     * <p>
     * <b>端点映射</b>：剖面以「归一化位置 = 参数 / 长度」为键（见 {@link RailRollProfile} 与
     * {@link RailGeometryCore#getRollRadians}），所以键 0 对应 railMath 参数 0 端
     * （{@code firstPosition}），并不恒等于 {@code Rail.position1}。
     * <ul>
     *   <li>两点剖面（节点派生滚转）：{@code roll1} 属于 {@code Rail.position1}，
     *       所以 {@code firstIsPosition1 == false} 时把两端对调；</li>
     *   <li>三点剖面（逐轨道超高）：{@code start} 属于 {@code position1}、{@code end} 属于
     *       {@code position2}，同样在 {@code firstIsPosition1 == false} 时对调；中间点的
     *       <b>归一化位置也要镜像成 {@code 1 - middleFraction}</b>（值本身不变）——
     *       中间控制点在物理上没有动，参数方向反过来后它的归一化位置正是 1 减去原值。
     *       于是镜像不变式成立：{@code rollProfileFor(pose,false).getRadians(f)}
     *       {@code == rollProfileFor(pose,true).getRadians(1 - f)}（已用
     *       {@code build/tmp_tiltprobe} 的探针数值验证）。<b>前提</b>：{@code middleFraction}
     *       必须已经以 {@code position1 → position2} 为 0 → 1（存盘约定见
     *       {@link #middleBreakpointFraction(RailGeometryCore, boolean)}）；存的是 railMath
     *       参数空间的值时，这一次镜像会把它推到接缝的镜像位置上。</li>
     * </ul>
     * 依据：MTR 上游更高版本的 {@code Rail} 构造器在 {@code reversePositions} 时，同样把
     * {@code tiltAngleDegrees1/tiltAngleDegrees2} 随 {@code position1/position2} 一起对调
     * （见 mtr4 参考源码 {@code Rail.java:144-154}）；4.0.5 的字节码里该处还没有倾斜参数，
     * 但端点对调规则完全一致（javap 已核实：{@code reversePositions} 时构造
     * {@code RailMath(position2, angle2, position1, angle1)}）。
     * <p>
     * <b>回退不变式</b>：{@code pose.hasRailTilt()} 为 false（老存档 / 从未编辑过逐轨道超高）
     * 时返回的剖面与引入本特性之前<b>逐位相同</b>，因此老轨道的渲染结果不会有任何变化。
     *
     * @param pose            附加姿态（语义顺序：{@code *1} 属于 {@code Rail.position1}）
     * @param firstIsPosition1 railMath 参数 0 端是否就是 {@code Rail.position1}
     */
    public static RailRollProfile rollProfileFor(RailPoseExtra pose, boolean firstIsPosition1) {
        if (pose.hasRailTilt()) {
            // 存盘的 middleFraction 恒以 position1 → position2 为 0 → 1（唯一产出点见
            // middleBreakpointFraction）；剖面以 railMath 参数为键，所以参数 0 端不是 position1 时
            // 正好要镜像一次，两次约定必须成对，缺一次峰值就落到接缝的镜像位置。
            final double middleFraction = pose.railTiltMiddleFraction;
            return firstIsPosition1
                    ? RailRollProfile.threePointDegrees(
                            pose.railTiltStartDegrees, pose.railTiltMiddleDegrees, pose.railTiltEndDegrees,
                            middleFraction)
                    : RailRollProfile.threePointDegrees(
                            pose.railTiltEndDegrees, pose.railTiltMiddleDegrees, pose.railTiltStartDegrees,
                            1.0D - middleFraction);
        }
        return firstIsPosition1
                ? RailRollProfile.twoPointDegrees(pose.roll1Degrees, pose.roll2Degrees)
                : RailRollProfile.twoPointDegrees(pose.roll2Degrees, pose.roll1Degrees);
    }

    /**
     * 「逐轨道超高」中间控制点应落在的归一化位置。
     * <p>
     * <b>不变式（本特性的唯一参考系约定）：这个归一化位置永远以 {@code position1 → position2}
     * 为 0 → 1。</b>渲染端 {@code rollProfileFor} 就按这个约定消费（剖面以「railMath 参数 / 长度」
     * 为键，参数 0 端不是 position1 时把中间位置镜像成 {@code 1 - fraction}，见
     * {@link #rollProfileFor(RailPoseExtra, boolean)}）；界面 {@code RailTiltConfigScreen} 也把它
     * 当成「从起点（position1）算起的百分比」显示，与渲染端一致。
     * <p>
     * 几何量本身是在 <b>railMath 参数空间</b> 里算出来的：参数 0 端在
     * {@code position1.compareTo(position2) > 0} 时是 position2（MTR 的 {@code Rail} 构造器会反着
     * 构造 {@code RailMath}）。所以参数空间的接缝位置 <b>不等于</b> position1 → position2 空间的位置，
     * 这里必须显式换算一次，否则反向建的轨道上中间控制点会落在接缝的<b>镜像</b>位置。
     * <p>
     * 曲线的两段圆弧在 {@code count1} 处相切/相接，超高在这里换坡才有物理意义；某一段退化
     * （{@code count1} 或 {@code count2} 为 0）时退回中点，也就是
     * {@link RailPoseExtra#DEFAULT_RAIL_TILT_MIDDLE_FRACTION}。
     * <p>
     * <b>不要照抄 MAGIC 的硬编码 0.5</b>：那只是直线轨的近似，曲线轨上中间控制点会落在错误的位置。
     *
     * @param geometryCore    按 railMath 参数顺序构建的几何内核
     * @param firstIsPosition1 该内核的参数 0 端是否就是 {@code Rail.position1}
     * @return 归一化位置（position1 → position2）；几何退化到无法给出接缝位置时为 0.5
     */
    public static double middleBreakpointFraction(RailGeometryCore geometryCore, boolean firstIsPosition1) {
        final double parameterSpaceFraction = parameterSpaceBreakpointFraction(geometryCore);
        // 参数空间 → position1 → position2 空间：参数 0 端是 position2 时，同一个点从 position1 量起的
        // 归一化位置正是 1 减去原值。0.5 是自镜像的不动点，所以退化回退值不受影响。
        return firstIsPosition1 ? parameterSpaceFraction : 1.0D - parameterSpaceFraction;
    }

    /**
     * 同上，但直接吃现成的 {@code railMath}（界面按当前几何估计接缝位置时用这个入口）。
     * <p>
     * 内核实例自己记着构造时的端点顺序（{@link #firstIsPosition1}），因此这里不需要调用方再传一遍，
     * 也就不会出现「几何按一种顺序建、参考系按另一种顺序算」的错配。
     */
    public static double middleBreakpointFraction(RailMath railMath) {
        if (railMath instanceof FangSuRailMath) {
            final FangSuRailMath fangSu = (FangSuRailMath) railMath;
            return middleBreakpointFraction(fangSu.core, fangSu.firstIsPosition1);
        }
        // 没有附加姿态的轨道装的仍是 MTR 原生 RailMath（其 getLength1/getLength2 是包私有，
        // com.fangsu.* 调不到），此时退回默认中点；服务端在第一次编辑超高时会先按当前几何
        // 建一个内核实例，因此不会真的用上这个保守值（见 RailTiltPackets）。
        return RailPoseExtra.DEFAULT_RAIL_TILT_MIDDLE_FRACTION;
    }

    /**
     * 两半径接缝在 <b>railMath 参数空间</b> 里的归一化位置 {@code count1 / length}（私有）：
     * 只作为 {@link #middleBreakpointFraction(RailGeometryCore, boolean)} 的中间量，
     * 不对外暴露——对外的一律是「以 position1 为 0 端」的值，避免参考系混用。
     */
    private static double parameterSpaceBreakpointFraction(RailGeometryCore geometryCore) {
        if (geometryCore == null) {
            return RailPoseExtra.DEFAULT_RAIL_TILT_MIDDLE_FRACTION;
        }
        final double count1 = geometryCore.getLength1();
        final double count2 = geometryCore.getLength2();
        final double length = count1 + count2;
        if (!(length > 0.0D) || !Double.isFinite(length)) {
            return RailPoseExtra.DEFAULT_RAIL_TILT_MIDDLE_FRACTION;
        }
        if (count1 <= DEGENERATE_SEGMENT_LENGTH || count2 <= DEGENERATE_SEGMENT_LENGTH) {
            // 退化（单段直线轨 / 圆弧轨）：没有接缝，取中点
            return RailPoseExtra.DEFAULT_RAIL_TILT_MIDDLE_FRACTION;
        }
        final double fraction = count1 / length;
        if (!(fraction > 0.0D) || fraction >= 1.0D) {
            return RailPoseExtra.DEFAULT_RAIL_TILT_MIDDLE_FRACTION;
        }
        return fraction;
    }

    // ==================== 委托：几何查询 ====================
    //
    // 【构造期守卫 —— 必须保留】
    // MTR 4.0.5 的 {@code RailMath} 构造器会调用<b>可被覆写</b>的方法：
    //     this.verticalRadius = Math.min(verticalRadius, getMaxVerticalRadius());   // RailMath.java:201
    // 而 getMaxVerticalRadius() 内部又调用 this.getLength()；随后构造器还用 render(...) 采样包围盒。
    // Java 的构造顺序是「先 super(...) 再赋值本类字段」，所以这些虚调用发生时 this.core 仍是 null。
    // 若不加守卫就会抛
    //     NullPointerException: ... because "this.core" is null
    // （实测：ghost 预览每帧新建 Rail → rebuildRailMath → 构造 FangSuRailMath 时崩溃）。
    // 守卫语义：构造期间一律退回父类实现，行为与 MTR 原生完全一致；构造完成后才走内核。
    // 包围盒也由父类先算一遍，随后在本类构造器末尾用内核结果整体覆盖。

    @Override
    public Vector getPosition(double rawValue, boolean reverse) {
        if (core == null) {
            return super.getPosition(rawValue, reverse);
        }
        final double[] position = core.getPosition(rawValue, reverse);
        return new Vector(position[0], position[1], position[2]);
    }

    @Override
    public double getLength() {
        return core == null ? super.getLength() : core.getLength();
    }

    @Override
    public Rail.Shape getShape() {
        return core == null ? super.getShape() : shape;
    }

    @Override
    public DoubleDoubleImmutablePair getHorizontalRadii() {
        if (core == null) {
            return super.getHorizontalRadii();
        }
        // 与 4.0.5 的 RailMath.getHorizontalRadii 同义：直线段返回 0，圆弧段返回 |r|
        return new DoubleDoubleImmutablePair(core.getHorizontalRadius1(), core.getHorizontalRadius2());
    }

    @Override
    public double getVerticalRadius() {
        return core == null ? super.getVerticalRadius() : core.getVerticalRadius();
    }

    @Override
    public double getMaxVerticalRadius() {
        return core == null ? super.getMaxVerticalRadius() : core.getMaxVerticalRadius();
    }

    // ==================== 委托：轨面渲染 ====================

    /**
     * 逐字复刻 4.0.5 {@code RailMath.render} 的分段与采样循环，但取内核算出的点。
     * <p>
     * 4.0.5 的回调契约是 10 个 double，顺序由 {@code RailMath.renderSegment} 的字节码确定
     * （{@code Rail 4.0.5} 的 {@code renderSegment}：slot32 = 半径 2 的角点、slot33 = 半径 1 的角点，
     * 回调依次压入「上一轮的 slot33、上一轮的 slot32、本轮 slot32、本轮 slot33」，即
     * <b>{@code (上一点·半径1, 上一点·半径2, 当前点·半径2, 当前点·半径1, 上一y, 当前y)}</b>）：
     * <pre>
     * (prev_r1.x, prev_r1.z, prev_r2.x, prev_r2.z,
     *  cur_r2.x,  cur_r2.z,  cur_r1.x,  cur_r1.z, prevY, y)
     * </pre>
     * 其中 {@code r1 = offsetRadius1}、{@code r2 = offsetRadius2}，二者相等时同一角点复用。
     * 本方法与 {@code RenderRails} 的消费端（{@code drawTexture} + {@code Direction.UP}）必须完全一致：
     * 顺序错了会交换四边形的角点、翻转绕序（面被剔除/朝向错误），所以这里严格照抄 4.0.5。
     * <p>
     * 与 4.0.5 的唯一差异：内核 {@code getOffsetPosition} 会把参数夹到 {@code [0, length]}，
     * 而 MTR 在极短轨道（{@code count < 0.5} 导致 increment = 0.5）的末端会略微外推。
     * 这一点只影响末段 0.1 格以内的采样，视觉不可见。
     */
    @Override
    public void render(RenderRail renderRail, double interval, float offsetRadius1, float offsetRadius2) {
        // 构造期守卫：父类构造器用它采样包围盒，此时 core 还没赋值（见上方说明）
        if (core == null) {
            super.render(renderRail, interval, offsetRadius1, offsetRadius2);
            return;
        }
        renderSegment(0.0D, core.getLength1(), interval, offsetRadius1, offsetRadius2, renderRail);
        renderSegment(core.getLength1(), core.getLength2(), interval, offsetRadius1, offsetRadius2, renderRail);
    }

    /**
     * 与 4.0.5 {@code RailMath.renderSegment} 等价的采样循环。
     *
     * @param rawValueOffset 本段起点在整条轨道上的参数偏移（第 2 段为 length1）
     * @param count          本段长度
     */
    private void renderSegment(
            double rawValueOffset, double count, double interval,
            float offsetRadius1, float offsetRadius2,
            RenderRail renderRail
    ) {
        // 4.0.5：increment = (count < 0.5 || interval <= 0) ? 0.5 : count / Math.round(count) * interval
        final double increment = count < 0.5D || interval <= 0.0D
                ? 0.5D
                : count / Math.round(count) * interval;
        final boolean sameRadius = offsetRadius1 == offsetRadius2;

        double[] previousCorner1 = null;
        double[] previousCorner2 = null;
        double previousY = 0.0D;

        // 4.0.5 的上界是 count + increment - 0.1（字面量 0.1，与内核注释中的证据一致）
        for (double i = 0.0D; i < count + increment - 0.1D; i += increment) {
            final double value = rawValueOffset + i;
            final double[] corner1 = core.getOffsetPosition(value, offsetRadius1);
            final double[] corner2 = sameRadius ? corner1 : core.getOffsetPosition(value, offsetRadius2);
            final double y = core.getPositionY(value);

            if (previousCorner1 != null) {
                // 严格对应 4.0.5：上一轮的「半径1、半径2」，再是本轮的「半径2、半径1」
                renderRail.renderRail(
                        previousCorner1[0], previousCorner1[2],
                        previousCorner2[0], previousCorner2[2],
                        corner2[0], corner2[2],
                        corner1[0], corner1[2],
                        previousY, y
                );
            }

            previousCorner1 = corner1;
            previousCorner2 = corner2;
            previousY = y;
        }
    }
}
