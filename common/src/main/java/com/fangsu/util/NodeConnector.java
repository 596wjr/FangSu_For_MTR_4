package com.fangsu.util;

import com.fangsu.blockEntities.BlockEntityMultiDirectionNode;
import com.fangsu.mappings.rail.RailPoseExtra;
import com.fangsu.mtr.AngleExtra;
import com.fangsu.mtr.rail.RailPoseExtraHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.data.RailType;
import org.mtr.mod.packet.PacketUpdateData;
import org.mtr.mod.packet.PacketUpdateLastRailStyles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 万向节点连接几何与建轨工具。
 * <p>
 * 提供：
 * <ul>
 *   <li>{@link #straightAngle(BlockPos, BlockPos)} — 两端点水平连线角度（直线轨道方向）</li>
 *   <li>{@link #maxRadiusTangentAngle(BlockPos, double, BlockPos)} — 未绑定端点的最大半径圆弧切向角</li>
 *   <li>{@link #getDirectionDegrees(Level, BlockPos)} / {@link #isConnectedAt(Level, BlockPos)} — 读取节点状态</li>
 *   <li>{@link #findConnectedEndpoints(BlockPos)} — 客户端查找连接到节点位置的其他端点</li>
 *   <li>{@link #createAndSendRail} — 服务端按连接器类型（限速/单向/站台/侧线/折返）构建并派发铁轨</li>
 *   <li>{@link #refreshNodeRail} — 服务端先校验候选几何、再按旧轨道属性（限速/单向/类型/样式）原地替换旧轨道</li>
 *   <li>{@link #hasValidGeometry} — 客户端安全（不发包、不改世界）的几何预检，供配置界面红字警告使用</li>
 * </ul>
 */
public final class NodeConnector {

    private NodeConnector() {
    }

    /**
     * 水平面上两端点的连线角度（度，0=E, 90=S, 180=W, 270=N）。用于直线轨道方向绑定。
     */
    public static double straightAngle(BlockPos a, BlockPos b) {
        final double dx = b.getX() - a.getX();
        final double dz = b.getZ() - a.getZ();
        return normalizeDegrees(Math.toDegrees(Math.atan2(dz, dx)));
    }

    /**
     * 双精度版本：用<b>节点锚点</b>（方块坐标 + 节点平移）而不是方块坐标算连线角度。
     * <p>
     * 节点平移是亚格量，整数版本会把 0.25 格的偏移直接丢掉，导致建轨时算出的"直线方向"
     * 与实际绘制出来的轨道方向不一致。偏移为 0 时两者结果完全相同。
     *
     * @param offsetA 端点 a 的锚点偏移 {@code {x, y, z}}（y 不参与水平角度）
     * @param offsetB 端点 b 的锚点偏移 {@code {x, y, z}}
     */
    public static double straightAngle(BlockPos a, double[] offsetA, BlockPos b, double[] offsetB) {
        final double dx = (b.getX() + offsetB[0]) - (a.getX() + offsetA[0]);
        final double dz = (b.getZ() + offsetB[2]) - (a.getZ() + offsetA[2]);
        return normalizeDegrees(Math.toDegrees(Math.atan2(dz, dx)));
    }

    /**
     * 计算"最大半径圆弧"在未绑定端点处的切向角。
     * <p>
     * 几何：固定端点 F 的切向为 fixedAngle，求过 free 点且在该处与方向相切的圆（唯一解），
     * 返回该圆在 free 点处的切向角。这是满足平滑连接的"最大半径"圆弧。
     *
     * @param fixed      已绑定端点位置
     * @param fixedAngle 已绑定端点方向（度）
     * @param free       未绑定端点位置
     * @return free 点处的切向角（度）
     */
    public static double maxRadiusTangentAngle(BlockPos fixed, double fixedAngle, BlockPos free) {
        final double fx = fixed.getX(), fz = fixed.getZ();
        final double px = free.getX(), pz = free.getZ();
        final double rad = Math.toRadians(fixedAngle);

        // 方向单位向量 (cos, sin) 在 (x, z) 平面（X 向东，Z 向南）
        final double dirX = Math.cos(rad);
        final double dirZ = Math.sin(rad);
        // 法线（垂直于方向）
        final double nX = -dirZ;
        final double nZ = dirX;

        final double dX = px - fx;
        final double dZ = pz - fz;
        final double nDotD = nX * dX + nZ * dZ;
        if (Math.abs(nDotD) < 1e-6) {
            // 法线平行于连线 → 共线，退化为直线
            return straightAngle(fixed, free);
        }
        final double dDotD = dX * dX + dZ * dZ;
        final double t = dDotD / (2 * nDotD);
        final double cx = fx + t * nX;
        final double cz = fz + t * nZ;

        // free 端径向 (free - C)；切向 = 径向在水平面内顺时针旋转 90°
        final double rX = px - cx;
        final double rZ = pz - cz;
        double tangX = -rZ;
        double tangZ = rX;

        // 固定端径向 = F - C；固定端切向（顺时针90°）应与给定方向一致，否则翻转 free 端切向以保持旋向一致
        final double fixRadX = fx - cx;
        final double fixRadZ = fz - cz;
        final double fixTangX = -fixRadZ;
        final double fixTangZ = fixRadX;
        final double dot = fixTangX * dirX + fixTangZ * dirZ;
        if (dot >= 0) {
            tangX = -tangX;
            tangZ = -tangZ;
        }

        return normalizeDegrees(Math.toDegrees(Math.atan2(tangZ, tangX)));
    }

    /**
     * 双精度版本的最大半径圆弧切向角：把两个端点换成<b>节点锚点</b>（方块坐标 + 节点平移）。
     * <p>
     * 与整数版本的区别仅在固定点/自由点各加了自己的水平偏移；偏移为 0 时结果完全相同。
     * 退化（法线与连线平行）时同样降级为 {@link #straightAngle(BlockPos, double[], BlockPos, double[])}。
     *
     * @param offsetFixed 固定点锚点偏移 {@code {x, y, z}}
     * @param offsetFree  自由点锚点偏移 {@code {x, y, z}}
     */
    public static double maxRadiusTangentAngle(BlockPos fixed, double[] offsetFixed, double fixedAngle, BlockPos free, double[] offsetFree) {
        final double fx = fixed.getX() + offsetFixed[0];
        final double fz = fixed.getZ() + offsetFixed[2];
        final double px = free.getX() + offsetFree[0];
        final double pz = free.getZ() + offsetFree[2];
        final double rad = Math.toRadians(fixedAngle);

        final double dirX = Math.cos(rad);
        final double dirZ = Math.sin(rad);
        final double nX = -dirZ;
        final double nZ = dirX;

        final double dX = px - fx;
        final double dZ = pz - fz;
        final double nDotD = nX * dX + nZ * dZ;
        if (Math.abs(nDotD) < 1e-6) {
            return straightAngle(fixed, offsetFixed, free, offsetFree);
        }
        final double dDotD = dX * dX + dZ * dZ;
        final double t = dDotD / (2 * nDotD);
        final double cx = fx + t * nX;
        final double cz = fz + t * nZ;

        final double rX = px - cx;
        final double rZ = pz - cz;
        double tangX = -rZ;
        double tangZ = rX;

        final double fixRadX = fx - cx;
        final double fixRadZ = fz - cz;
        final double fixTangX = -fixRadZ;
        final double fixTangZ = fixRadX;
        final double dot = fixTangX * dirX + fixTangZ * dirZ;
        if (dot >= 0) {
            tangX = -tangX;
            tangZ = -tangZ;
        }

        return normalizeDegrees(Math.toDegrees(Math.atan2(tangZ, tangX)));
    }

    // ==================== 节点锚点平移（P2） ====================

    /**
     * 读取某方块位置处的节点锚点平移 {@code {x, y, z}}（格）。
     * 万向节点取方块实体里的值；普通 MTR 节点或任意非节点方块返回全 0（原版行为）。
     */
    public static double[] readNodeOffset(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return new double[]{0.0D, 0.0D, 0.0D};
        }
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BlockEntityMultiDirectionNode node) {
            return new double[]{node.getOffsetX(), node.getOffsetY(), node.getOffsetZ()};
        }
        return new double[]{0.0D, 0.0D, 0.0D};
    }

    /**
     * 读取某方块位置处的万向节点方块实体；非万向节点（普通 MTR 节点或别的方块）返回 {@code null}。
     * <p>
     * 客户端 / 服务端都安全：只做一次 {@code getBlockEntity} 查询，不发包、不改世界、不加载区块。
     * 姿态读取（平移 / 俯仰 / 翻滚 / 外轨超高开关 / 半轨距）共用本方法，避免每个量各查一次方块实体。
     */
    @Nullable
    private static BlockEntityMultiDirectionNode multiDirectionNodeAt(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        final BlockEntity be = level.getBlockEntity(pos);
        return be instanceof BlockEntityMultiDirectionNode node ? node : null;
    }

    /**
     * 端点的滚转贡献（外轨超高开关的唯一作用点）。
     * <p>
     * <b>开关关闭时返回 0</b>，于是 {@code RailPoseExtra.hasRoll()} 为 false，
     * 几何内核的中心线抬升 {@code 半轨距·|sin(roll)|} 整体消失，轨道回到无超高的水平截面。
     * 节点自身仍保留 {@code rollDeg}（模型照常倾斜、界面照常显示），只是不再参与轨道几何。
     * <p>
     * <b>俯仰（纵坡）不经过本方法</b>：纵坡是独立功能，无论开关如何都照常进入
     * {@code RailPoseExtra.pitch1Degrees/pitch2Degrees}（它另有自己的节点帧 → 轨道帧换算，
     * 见 {@link #readRailPose}）。
     */
    private static double rollContribution(BlockEntityMultiDirectionNode node) {
        return node.isSuperelevationEnabled() ? node.getRollDegrees() : 0.0D;
    }

    // ==================== 翻滚角的节点帧 → 轨道帧换算（P4b-3 / FIX 3） ====================
    //
    // 注：俯仰（纵坡）走的是<b>另一套</b>换算，见下方「俯仰角的节点帧 → 轨道帧换算（FIX-PITCH-2）」。
    // 翻滚只要 ±1 的帧符号就够：几何内核的中心线抬升 半轨距·|sin(roll)| 对符号不敏感（抬哪一侧），
    // 参数方向那一步由渲染层的 railFrameAngle 补上。而俯仰被内核<b>直接</b>当作参数系的端点切线
    // 消费，没有 railFrameAngle 那一步，所以它必须自己做完整投影（pSign × dot，见 pitchFrameAngle）。
    // 两个特性因此<b>刻意分成两个函数</b>：改其中一个之前先读清楚另一个，别把两者合并。
    //
    // 问题：节点的翻滚角是「节点自身方向 d 的右手侧抬高」，但轨道几何 / 渲染的翻滚参数是
    // 定义在**轨道参数系**上的（渲染帧约定：roll > 0 抬升 Rail.position1 → Rail.position2
    // 的右手侧，见 RailRollRenderHelper 的「滚转符号约定」）。而 MTR 的
    // Rail.position1/position2 是**玩家建轨顺序**，且 MTR 用
    // reversePositions = position1.compareTo(position2) > 0（Position 按 x → y → z 字典序）
    // 决定 railMath 的参数方向 —— 也就是说轨道参数方向与「节点的方向」毫无关系，
    // 同一条连线换个方向建、或换个端点先点，参数方向就可能整个反过来。
    // 于是同一个节点的同一个翻滚角，会在两条相邻轨道上落到相反的一侧（Symptom 2 的 V 形扭结）。
    //
    // 解法：把节点方向投影到轨道方向上，用投影的符号把角度换算到轨道帧。定义
    //     dx/dz = 从 Rail.position1 指向 Rail.position2 的水平向量（= 建轨方向 A，两端同用）
    //     s     = sign(dot(d, A)) —— d 与 A 同向 → +1，反向 → -1
    // 依据：渲染帧抬升的是 A 的右手侧；在 position1 端 A 是**出发**方向、在 position2 端 A 是
    // **到达**方向，两者都是「轨道参数增大方向」，所以两端都用同一个 A，不需要再翻符号。
    // （若改为「各端点指向另一端」的向量，position2 端会等于 -A，符号正好反掉 —— 那是错的。）
    // 换算后的值随即由 FangSuRailMath 的 firstIsPosition1 交换写进内核剖面，
    // 因此 com/fangsu/mappings/rail/* 内核保持逐字节不变。
    //
    // 不变量：本换算只是对单个端点值乘 ±1，
    //   - 正是 ±1 所以「是否为 0」「|·| 的大小比较」全部不变 → 下面的半轨距权威判据不受影响；
    //   - 内核的中心线抬升 半轨距·|sin(roll)| 对符号不敏感 → 几何抬升量不变；
    //   - 只有渲染帧的「抬哪一侧」会跟着变，这正是本修复的目的。

    /** 方向投影判定的死区：|dot| 小于它时视为「节点方向与轨道方向近乎垂直」，不翻转符号。 */
    private static final double FRAME_SIGN_DEADBAND = 1.0E-3D;

    /** 上述死区回退是否已经报告过（一次性日志，避免刷屏）。 */
    private static boolean warnedFrameSignDeadband = false;

    /**
     * 单端点帧符号（<b>只服务翻滚</b>；俯仰用的是 {@link #pitchFrameAngle}，不要拿本方法算俯仰）：
     * 把「节点自身方向的右手侧抬高」换算成「轨道参数系的某一侧抬高」。
     *
     * @param dx   从 {@code Rail.position1} 指向 {@code Rail.position2} 的水平向量 X 分量（不必归一化）
     * @param dz   同上，Z 分量
     * @param node 该端点的万向节点（{@code null} 表示普通 MTR 节点 / 非节点方块 → 恒为 +1）
     * @return {@code +1}（节点方向与轨道方向同向）或 {@code -1}（反向）；
     *         两者近乎垂直（|dot| &lt; {@link #FRAME_SIGN_DEADBAND}）时回退为 {@code +1}，
     *         并打印一次性 warn 让「从没转过方向的节点」可见。
     *         <p>
     *         <b>这个 +1 回退只对翻滚成立</b>（翻滚的抬升量对符号不敏感，回退只是「抬哪一侧」的
     *         约定问题）。俯仰<b>绝不能</b>这样回退：那会让同一节点两条轨道拿到相同符号并把坡度
     *         算成 tan(pitch) 而不是 0，渲染出来就是驼峰 —— 这正是 FIX-PITCH-2 修掉的缺陷。
     *         <p>
     *         <b>这是翻滚换算唯一改动符号的地方</b>，不要在别处再翻一次。
     */
    private static double frameSign(double dx, double dz, BlockEntityMultiDirectionNode node) {
        if (node == null) {
            return 1.0D;
        }
        final double len = Math.hypot(dx, dz);
        if (len < 1.0E-9D) {
            return 1.0D;
        }
        // 节点方向的水平单位向量：getDirectionDegrees() 是与 straightAngle 同一套的罗盘角
        // （0=E、90=S、180=W、270=N，即 atan2(dz, dx) 的度数），所以方向向量就是 (cos, sin)。
        final double nodeRadians = Math.toRadians(node.getDirectionDegrees());
        final double dot = (dx / len) * Math.cos(nodeRadians) + (dz / len) * Math.sin(nodeRadians);
        if (Math.abs(dot) < FRAME_SIGN_DEADBAND) {
            // 节点方向与轨道方向近乎垂直（例如从未旋转绑定过的节点，方向仍是默认 0=东，
            // 却连了一条南北向的轨道）。此时「节点方向的右手侧」在轨道横断面上没有明确对应，
            // 保持角度符号不变（等价于按参数方向右侧处理），并一次性报出来。
            if (!warnedFrameSignDeadband) {
                warnedFrameSignDeadband = true;
                com.fangsu.Main.LOGGER.warn("[NodeRoll] 万向节点 {} 的方向 {}° 与轨道方向近乎垂直（dot={}），"
                                + "翻滚角无法换算到轨道帧，按 +1 处理；请检查该节点是否已旋转绑定到轨道方向",
                        node.getBlockPos(), node.getDirectionDegrees(), dot);
            }
            return 1.0D;
        }
        return Math.signum(dot);
    }

    // ==================== 俯仰角的节点帧 → 轨道帧换算（P4a / FIX-PITCH-2） ====================
    //
    // 【权威表述，请勿「简化」】节点 N 的 pitchDeg 定义的是<b>节点自身方向 d 上的纵坡</b>：
    // tan(pitch) = 沿 d 水平前进 1 格时的高度增量（语义推导见
    // BlockEntityMultiDirectionNode.applyNodeModelTilt）。轨道要落在同一个高度场上，因此在节点处
    // 每条相连轨道的切线必须满足
    //     slope_kernel(端点) = tan(pitch) · dot(d, incrDir)
    // 其中 slope_kernel 是内核 Hermite 的端点切线 dy/d(param)（内核 hermitePositionY 用
    // slope·length，所以写进 RailPoseExtra 的角度取 tan 后<b>就是</b>这个切线值），
    // incrDir 是该轨道在内核参数系里「参数增大方向」的水平单位向量。
    //
    // incrDir 怎么来：内核参数 0 端是 firstPosition、沿 secondPosition 增大；而 MTR 在
    // reversePositions（position1.compareTo(position2) > 0）时把内核构造成
    // (position2, …, position1, …)，所以
    //     incrDir = pSign · chordUnit,   chordUnit = 单位(position1 → position2)（锚点差，含节点平移）
    //     pSign   = +1（pos1 按 MTR 的 Position 字典序 ≤ pos2）/ -1（否则）
    // 关键：incrDir 在<b>两端是同一个向量</b>。参数是从 position1 一路增大到 position2，所以位置 2 端的
    // 切线方向仍然是「从 position1 指向 position2」（到达方向），<b>不是</b>反向。因此不需要任何
    // 「端点在哪一头就翻一次符号」的额外因子；符号差异全部来自两条轨道各自的 incrDir。
    //
    // 关于用户说的「一侧按正值、一侧按负值」：那只是两条轨道参数方向相反时<b>存储值</b>的表现，
    // 不是要求本身。真正的判据是「节点处纵坡连续」——两条轨道在 N 处落在同一个倾斜平面上。
    // 共线时 incrDir 相反 → 存储值必然一正一负；两条轨道的建轨顺序相同时（pSign 相同）也可能
    // 存储成 +/+，那同样是正确的。判定标准只有一条：斜率 = tan(pitch) · dot(d, incrDir)。
    //
    // 与 D-A 版（frameSign · pSign，只取符号）的两点区别：
    //   1) 不再只取符号，而是把 dot 的<b>大小</b>也乘进去：节点方向与轨道斜交时，轨道沿自己的方向
    //      只分到 cos(夹角) 的坡度（节点方向垂直于轨道时正好 0 = 该处水平）。这是「落在同一个倾斜
    //      平面上」的必然结果；节点方向沿/逆轨道（自动绑定）时 |dot| = 1，结果与 D-A 逐位相同。
    //   2) 垂直情形<b>不再回退成 +1</b>。回退会让同一节点的两条轨道因为 pSign 不同而拿到不同的
    //      存储符号（一条 +tan、一条 −tan），渲染出来正是报告里的驼峰。投影到 0 后两条轨道都水平，
    //      坡度自然连续；只保留一次 warn 提示「该节点方向与轨道垂直，俯仰在这条轨道上没有分量」。
    //
    // 为什么必须用 atan：写进 RailPoseExtra 的是<b>角度</b>，内核会对它取 tan 当切线。要求切线等于
    // tan(pitch)·k，所以存的角度必须是 atan(tan(pitch)·k)，<b>不能</b>写成 pitch·k ——
    // 那是角度线性缩放（tan(pitch·k) ≠ tan(pitch)·k）。k = pSign · dot(d, chordUnit) ∈ [-1, 1]，
    // 所以 |存进去的角度| ≤ |pitch| ≤ MAX_PITCH_DEG，不存在除零、溢出、Hermite 爆掉的风险。
    //
    // pSign 的字典序必须照 MTR 的 Position.compareTo（x → y → z），<b>不能</b>用
    // net.minecraft.core.BlockPos.compareTo：1.20.1 的 Vec3i.compareTo 是 y → z → x，斜向轨道上
    // 两者会给出相反的顺序（用真实类实测：BlockPos(0,64,5) vs (10,64,0) → +5，Position → -1），
    // 而 MTR 的 reversePositions 用的就是 Position.compareTo。pSign 反了整条轨道的俯仰会整体反号。
    //
    // 数值验证（build/tmp/pitchsign，真实内核 RailGeometryCore + 真实 FangSuRailMath 端点映射）：
    //   共线直线 × 两条轨道的 2×2 建轨顺序 × {沿 d、逆 d、垂直 d} × pitch = ±10°：
    //   修复前：垂直方向 8 个组合里 4 个在节点处两侧坡度反号（驼峰），另 4 个两侧一致但坡度应为 0；
    //   修复后：24 个组合全部满足 slope = tan(pitch)·dot(d, +x)，且节点两侧完全一致。

    /** 俯仰投影中「节点方向与轨道方向近乎垂直」的判定阈值：只用于一次性日志，<b>不影响取值</b>。 */
    private static final double PITCH_PERPENDICULAR_EPSILON = 1.0E-3D;

    /** 俯仰垂直情形的一次性日志标记（与翻滚的死区标记分开，互不吞掉对方提示）。 */
    private static boolean warnedPitchPerpendicular = false;

    /**
     * 单端点纵坡：把「节点方向 d 上的纵坡 pitch」<b>投影</b>到这条轨道的内核参数方向上，
     * 返回写入 {@code RailPoseExtra.pitchNDegrees} 的角度（推导见上方「俯仰角的节点帧 → 轨道帧换算」）。
     * <p>
     * {@code 存进去的角度 = atan( tan(pitch) · pSign · dot(d, chordUnit) )}。
     *
     * @param dx    从 {@code Rail.position1} 指向 {@code Rail.position2} 的水平向量 X 分量（锚点差，不必归一化）
     * @param dz    同上，Z 分量
     * @param node  该端点的万向节点
     * @param pSign MTR 的 reversePositions 符号（+1 = 内核参数 0 端是 position1），见 {@link #parameterSign}
     * @return 内核参数系里的纵坡角（度）；{@code pitch == 0} 或水平方向退化时返回 {@code +0.0}
     */
    private static double pitchFrameAngle(double dx, double dz, BlockEntityMultiDirectionNode node, double pSign) {
        final double pitch = node.getPitchDegrees();
        if (pitch == 0.0D) {
            // 默认姿态不变式：0 必须原样返回 +0.0（RailPoseExtra.DEFAULT 逐位相等，isDefault() 才成立）
            return 0.0D;
        }
        final double len = Math.hypot(dx, dz);
        if (len < 1.0E-9D) {
            // 水平方向退化（两个锚点重合）：没有 incrDir，就没有可承载的纵坡
            return 0.0D;
        }
        final double nodeRadians = Math.toRadians(node.getDirectionDegrees());
        final double dot = (dx / len) * Math.cos(nodeRadians) + (dz / len) * Math.sin(nodeRadians);
        final double k = pSign * dot;
        if (Math.abs(k) >= 1.0D - 1.0E-12D) {
            // 节点方向正好沿/逆这条轨道（自动绑定）：直接给出 ±pitch，
            // 与 D-A 版本的存储角<b>逐位相同</b> —— 这一支保证自动绑定的轨道几何没有任何回归。
            return k > 0.0D ? pitch : -pitch + 0.0D;
        }
        if (Math.abs(dot) < PITCH_PERPENDICULAR_EPSILON && !warnedPitchPerpendicular) {
            warnedPitchPerpendicular = true;
            com.fangsu.Main.LOGGER.warn("[NodePitch] 万向节点 {} 的方向 {}° 与轨道方向近乎垂直（dot={}），"
                            + "俯仰在该轨道上没有分量，节点处按水平处理；请检查该节点是否已旋转绑定到轨道方向",
                    node.getBlockPos(), node.getDirectionDegrees(), dot);
        }
        return Math.toDegrees(Math.atan(Math.tan(Math.toRadians(pitch)) * k));
    }

    /**
     * MTR 的 {@code reversePositions} 符号：{@code position1.compareTo(position2) <= 0 ? +1 : -1}。
     * <p>
     * <b>必须</b>复刻 {@code org.mtr.core.data.Position.compareTo} 的字典序 <b>x → y → z</b>，
     * 不能用 {@code net.minecraft.core.BlockPos.compareTo}：1.20.1 的 {@code Vec3i.compareTo} 是
     * <b>y → z → x</b>，在斜向轨道（dx、dz 都不为 0）上两者会给出相反的顺序，pSign 一错俯仰就整条反号。
     * 已用真实类实测：{@code BlockPos(0,64,5).compareTo(BlockPos(10,64,0)) = +5} 而
     * {@code Position(0,64,5).compareTo(Position(10,64,0)) = -1}。
     */
    private static double parameterSign(BlockPos pos1, BlockPos pos2) {
        return compareXyz(pos1, pos2) <= 0 ? 1.0D : -1.0D;
    }

    /** 与 {@code Position.compareTo} 同为 x → y → z 字典序的整数比较（方块坐标即 MTR 的 Position）。 */
    private static int compareXyz(BlockPos a, BlockPos b) {
        if (a.getX() != b.getX()) {
            return Integer.compare(a.getX(), b.getX());
        }
        if (a.getY() != b.getY()) {
            return Integer.compare(a.getY(), b.getY());
        }
        return Integer.compare(a.getZ(), b.getZ());
    }

    /**
     * 读取某方块位置处的「单端附加姿态」：只有端点 1 的字段被填充，端点 2 全零。
     * 调用方需要自行决定这个姿态属于轨道的哪一端（见 {@link #readRailPose}）。
     * <p>
     * <b>P4a</b>：俯仰 / 翻滚 / 半轨距从这里开始进入轨道姿态：
     * <ul>
     *   <li>{@code pitch1Degrees} = 节点 {@code pitchDeg} <b>原样</b>给出（<b>不做</b>节点帧 → 轨道帧
     *       换算：这里没有另一端的锚点，算不出轨道弦向）。真正写进轨道的换算在
     *       {@link #readRailPose} 里，用的是 {@link #pitchFrameAngle}；本方法只给单端诊断/预览用，
     *       所以它的俯仰是<b>节点帧</b>的值，不是内核参数系的值。驱动几何内核的三次 Hermite
     *       竖向剖面（纵坡），<b>不受外轨超高开关影响</b>；</li>
     *   <li>{@code roll1Degrees} = 节点 {@code rollDeg} —— 但外轨超高开关关闭时写 0（见
     *       {@link #rollContribution}），驱动内核的 {@code 半轨距·|sin(roll)|} 中心线抬升；</li>
     *   <li>{@code halfGauge} = 节点 {@code rollOffsetM}（半轨距，米）。</li>
     * </ul>
     * 非万向节点（普通 MTR 节点 / 空位置）贡献全零 + 默认半轨距，即 {@link RailPoseExtra#DEFAULT}，
     * 完全等价于原版行为。
     */
    public static RailPoseExtra readNodePose(Level level, BlockPos pos) {
        final BlockEntityMultiDirectionNode node = multiDirectionNodeAt(level, pos);
        if (node == null) {
            return RailPoseExtra.DEFAULT;
        }
        return new RailPoseExtra(
                node.getOffsetX(), node.getOffsetY(), node.getOffsetZ(),
                0.0D, 0.0D, 0.0D,
                node.getRollOffsetM(),
                node.getPitchDegrees(), 0.0D,
                rollContribution(node), 0.0D
        );
    }

    /**
     * 合成一条轨道的附加姿态：{@code offset1} 属于 {@code Rail.position1}（= 建轨时的 pos1），
     * {@code offset2} 属于 {@code Rail.position2}。
     * <p>
     * 这正是 {@link Rail#newRail} / {@code newPlatformRail} 等工厂的参数顺序，因此可以直接与
     * {@code RailPoseExtra} 的端点语义对应，无需关心 MTR 内部的 {@code reversePositions}
     * （那段对调只影响 {@code railMath} 的参数化方向，由 {@code FangSuRailMath} 处理）。
     * <p>
     * <b>P4a：端点 → 姿态字段的完整映射</b>
     * <ul>
     *   <li>{@code pitch1/2Degrees} = 该端点万向节点的 {@code pitchDeg}（非万向节点端点 → 0），
     *       <b>但先用 {@link #pitchFrameAngle} 投影到内核参数系</b>
     *       （{@code atan(tan(pitch) · pSign · dot(d, chordUnit))}，推导见方法尾部的
     *       「俯仰角的节点帧 → 轨道帧换算（FIX-PITCH-2）」）。写入内核的三次 Hermite 纵坡剖面
     *       （端点切线 = {@code tan(轨道帧俯仰角)}），<b>不受外轨超高开关门控</b>。
     *       节点方向沿/逆轨道时该投影退化为 ±pitch（与 D-A 版逐位相同）。</li>
     *   <li>{@code roll1/2Degrees} = 该端点万向节点的 {@code rollDeg}，<b>但当该端点的外轨超高开关
     *       关闭时写 0</b>（{@link #rollContribution}）。开关是按节点存的，所以门控也是逐端点的：
     *       一端关、另一端开时，只有关闭端的滚转被抹掉，另一端照常贡献。
     *       写 0 使 {@code hasRoll()} 为 false，内核的 {@code 半轨距·|sin(roll)|} 抬升随之消失。
     *       <p>
     *       <b>P4b-3 / FIX 3</b>：写入前还要乘一次「端点帧符号」{@link #frameSign}，
     *       把「节点自身方向的右手侧抬高」换算成「轨道参数系的某一侧抬高」。
     *       纯 ±1 缩放：0 / 非 0 与 {@code |·|} 的全部比较都不受影响，
     *       但对渲染的「抬哪一侧」是决定性的（修 Symptom 2 的 V 形扭结）。
     *       注意 {@code roll1Degrees/roll2Degrees} 因此是<b>轨道帧</b>的值，
     *       不再是节点方向的原始角度符号。</li>
     *   <li>{@code halfGauge} = <b>整条轨道只取一个值</b>（{@code RailPoseExtra} 就是这么设计的）。
     *       取值来源是「<b>实际贡献滚转</b>的端点」，贡献判定复用 {@link #rollContribution}：
     *       该端点 {@code superelevation} 开关打开<b>且</b> {@code rollDeg != 0}。规则：
     *       <ul>
     *         <li>只有一端贡献滚转 → 用那一端的 {@code rollOffsetM}；</li>
     *         <li>两端都贡献滚转 → 用 {@code |rollDeg|} 较大的一端（{@code |rollContribution|} 等价）；
     *             两者完全相等时取端点 1（{@code Rail.position1}）；</li>
     *         <li>两端都不贡献（纯普通 MTR 轨道、或开关全关 / 角度全为 0）→
     *             {@link RailPoseExtra#DEFAULT_HALF_GAUGE}。</li>
     *       </ul>
     *       因此结果<b>不依赖两个端点的传入顺序</b>，唯一例外是两端 {@code |rollDeg|} <b>完全相等</b>
     *       的平局情形，那时固定取端点 1 以保证确定性。
     *       注意判据是「实际贡献」而非「是不是万向节点」：端点 1 的开关关着而端点 2 开着时，
     *       用的是端点 2 的半轨距，避免「已关闭节点的轨距」去配「另一端的滚转」。</li>
     * </ul>
     * <b>默认姿态不变式</b>：两端都是普通 MTR 节点、或万向节点的俯仰 / 翻滚都是 0 时，
     * 返回的姿态仍满足 {@link RailPoseExtra#isDefault()}（{@code isDefault()} 不看 {@code halfGauge}），
     * 因此 {@code RailMixin} 会继续安装 MTR 原生 {@code RailMath}，原版轨道几何逐位不变。
     */
    public static RailPoseExtra readRailPose(Level level, BlockPos pos1, BlockPos pos2) {
        final BlockEntityMultiDirectionNode node1 = multiDirectionNodeAt(level, pos1);
        final BlockEntityMultiDirectionNode node2 = multiDirectionNodeAt(level, pos2);
        final double[] offset1 = node1 == null
                ? new double[]{0.0D, 0.0D, 0.0D}
                : new double[]{node1.getOffsetX(), node1.getOffsetY(), node1.getOffsetZ()};
        final double[] offset2 = node2 == null
                ? new double[]{0.0D, 0.0D, 0.0D}
                : new double[]{node2.getOffsetX(), node2.getOffsetY(), node2.getOffsetZ()};
        // 翻滚贡献：复用 rollContribution（开关关闭 → 0），再乘端点帧符号换算到轨道参数系
        // （见 frameSign 上方的「翻滚角的节点帧 → 轨道帧换算」）。
        // 换算只乘 ±1，所以下面半轨距判据里的「是否为 0」「|·| 大小比较」全部不变。
        // 末尾的 + 0.0D 只为把 「0 × (-1) = -0.0」 归一成 +0.0：
        // 默认姿态必须逐位等于 RailPoseExtra.DEFAULT（isDefault() 对 ±0 都成立，但序列化出来
        // 的字符串会差一个负号，没必要引入这种差异）。
        final double frameDx = (pos2.getX() + offset2[0]) - (pos1.getX() + offset1[0]);
        final double frameDz = (pos2.getZ() + offset2[2]) - (pos1.getZ() + offset1[2]);
        final double contribution1 = node1 == null
                ? 0.0D
                : frameSign(frameDx, frameDz, node1) * rollContribution(node1) + 0.0D;
        final double contribution2 = node2 == null
                ? 0.0D
                : frameSign(frameDx, frameDz, node2) * rollContribution(node2) + 0.0D;
        // 半轨距：整条轨道一个值，只从「实际贡献滚转」的端点取，与传入顺序无关
        // （唯一例外：两端 |rollDeg| 完全相等时固定取端点 1，保证确定性）
        final double halfGauge;
        if (contribution1 != 0.0D && contribution2 == 0.0D) {
            // 只有端点 1 贡献滚转
            halfGauge = node1.getRollOffsetM();
        } else if (contribution2 != 0.0D && contribution1 == 0.0D) {
            // 只有端点 2 贡献滚转（端点 1 开关关闭或其角度为 0 时不再抢占权威值）
            halfGauge = node2.getRollOffsetM();
        } else if (contribution1 != 0.0D) {
            // 两端都贡献滚转：|rollDeg| 大者优先；完全相等时取端点 1
            halfGauge = Math.abs(contribution1) >= Math.abs(contribution2)
                    ? node1.getRollOffsetM()
                    : node2.getRollOffsetM();
        } else {
            // 两端都不贡献滚转：纯普通 MTR 轨道，或开关全关 / 角度全为 0
            halfGauge = RailPoseExtra.DEFAULT_HALF_GAUGE;
        }
        // ==================== 俯仰角的节点帧 → 轨道帧换算（FIX-PITCH-2） ====================
        //
        // 完整推导 / 为什么必须用 atan / 为什么垂直情形不能回退 +1 / 为什么 pSign 必须用 MTR 的
        // Position 字典序，全部写在 pitchFrameAngle 上方的「俯仰角的节点帧 → 轨道帧换算」注释里，
        // 这里是它的应用点，不再重复。一句话版本：
        //     存进内核的角度 = atan( tan(pitch) · pSign · dot(节点方向, 轨道弦单位向量) )
        // 与 D-A 的关系：D-A 版（frameSign · pSign · pitch，只取 ±1 符号）正是它在 |dot| = 1
        // （节点方向沿/逆轨道，即自动绑定）时的特例，由 pitchFrameAngle 的饱和分支逐位保持。
        //
        // pSign 的唯一作用：把「position1→position2 的弦向」换成「内核参数增大方向」。
        // 它不能折进 pitchFrameAngle（那是 per-endpoint 的投影），也不该由 FangSuRailMath 的
        // firstIsPosition1 代劳 —— 后者只负责「哪个端点拥有哪个 pitch 字段」的配对，不改变角度所在帧。
        // 翻滚则相反：它只乘 frameSign，绝不能再乘 pSign（参数方向那一步由渲染层 railFrameAngle 补）。
        //
        // 不变量：
        //   - pitch == 0 时 pitchFrameAngle 直接给 +0.0，末尾 + 0.0D 再把 -0.0 归一 → 默认姿态逐位等于 DEFAULT；
        //   - |pitchFrameAngle| ≤ |pitch|，内核 Hermite 的开关（任一为非 0）与幅度不越界；
        //   - 不触碰翻滚，也不触碰半轨距判据。
        final double pSign = parameterSign(pos1, pos2);
        return new RailPoseExtra(
                offset1[0], offset1[1], offset1[2],
                offset2[0], offset2[1], offset2[2],
                halfGauge,
                // 俯仰：纵坡不受外轨超高开关影响；投影到内核参数系（非万向节点端点贡献 0）
                node1 == null ? 0.0D : pitchFrameAngle(frameDx, frameDz, node1, pSign) + 0.0D,
                node2 == null ? 0.0D : pitchFrameAngle(frameDx, frameDz, node2, pSign) + 0.0D,
                // 翻滚：按各端点自己的外轨超高开关门控（关闭 → 0）
                contribution1,
                contribution2
        );
    }

    /** 把附加姿态写入轨道并重建几何（姿态为默认时等价于还原成 MTR 原生 RailMath）。 */
    public static void applyPose(Rail rail, RailPoseExtra pose) {
        RailPoseExtraHolder.apply(rail, pose);
    }

    /**
     * 把某个端点标记为「已连接」：万向节点写 BE 的 {@code connected}，普通 MTR 节点写 blockstate
     * 的 {@code IS_CONNECTED}。
     * <p>
     * <b>何时需要它</b>：MTR 删除轨道时会调用 {@code BlockNode.resetRailNode}（已用 javap 核对到
     * {@code PacketDeleteData.lambda$runServerInbound$0 → BlockNode.resetRailNode}），
     * 本项目的 {@code BlockNodeMixin} 把万向节点的 {@code connected} 也一并清成 false。
     * 该复位只对「删除后已无轨道」的端点发生，而且整条回调链是<b>延迟</b>执行的
     * （{@code Init.sendMessageC2S → minecraftServer.execute}），所以凡是「新建/重建出轨道」的路径
     * 都应在成功之后显式标记一次，让 {@code connected} 与「节点确实有轨道」保持一致。
     * <p>
     * <b>注意</b>：轨道刷新（{@link #refreshNodeRail}）走的是原地替换、不删除旧轨，
     * 因此它本身不会触发 {@code resetRailNode}；调用方仍会补一次本方法，
     * 使「轨道存在 → 节点已连接」这条不变式在任何时序下都成立（服务端始终是 {@code connected} 的权威）。
     */
    public static void markConnected(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BlockEntityMultiDirectionNode node) {
            node.setConnected(true);
            return;
        }
        // 普通 MTR 节点：设置 blockstate IS_CONNECTED=true（与连接器建轨后的处理一致）
        final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BlockNode) {
            level.setBlock(pos, state.setValue(BlockNode.IS_CONNECTED.data, true),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
    }

    /**
     * 归一化角度到 [0, 360)。
     */
    public static double normalizeDegrees(double deg) {
        deg = deg % 360.0;
        if (deg < 0) deg += 360.0;
        return deg;
    }

    /**
     * 读取节点方向的度数。万向节点取 BE NBT；普通节点取 blockstate 角度。
     */
    public static double getDirectionDegrees(Level level, BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BlockEntityMultiDirectionNode node) {
            return node.getDirectionDegrees();
        }
        return BlockNode.getAngle(new org.mtr.mapping.holder.BlockState(level.getBlockState(pos)));
    }

    /**
     * 读取方块位置处的连接状态。万向节点读 BE NBT；普通 MTR 节点读 blockstate。
     */
    public static boolean isConnectedAt(Level level, BlockPos pos) {
        final BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof BlockEntityMultiDirectionNode node) {
            return node.isConnected();
        }
        final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BlockNode) {
            return state.getValue(BlockNode.IS_CONNECTED.data);
        }
        return false;
    }

    /**
     * 客户端：查找连接到某节点位置的其他端点（读取 MinecraftClientData.positionsToRail）。
     * 仅在客户端调用（数据已本地同步）。
     */
    public static List<BlockPos> findConnectedEndpoints(BlockPos nodePos) {
        final List<BlockPos> result = new ArrayList<>();
        final Position position = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(nodePos));
        final Map<Position, Rail> connections = org.mtr.mod.client.MinecraftClientData.getInstance().positionsToRail.get(position);
        if (connections == null) return result;
        for (final Position other : connections.keySet()) {
            result.add(new BlockPos((int) other.getX(), (int) other.getY(), (int) other.getZ()));
        }
        return result;
    }

    /**
     * 服务端：以两个端点位置 + 两个角度，按连接器类型构建一条铁轨并派发到服务端数据。
     * <p>
     * 角度用 {@link AngleExtra} 生成精确值，避免 22.5° 快照。调用需在服务端线程。
     *
     * @param serverWorld 服务端世界（org.mtr.mapping.holder.ServerWorld）
     * @param pos1        端点 1
     * @param angle1      端点 1 角度（度）
     * @param pos2        端点 2
     * @param angle2      端点 2 角度（度）
     * @param railType    连接器类型（限速/站台/侧线/折返等）
     * @param isOneWay    是否单向（反向限速 0，渲染单向箭头）
     * @param uuid        玩家 UUID，用于应用玩家最后使用的轨道样式（null 时用默认样式）
     * @return 轨道是否创建成功（false = RailMath 几何不成立，如两端绑定方向与连线冲突）
     */
    public static boolean createAndSendRail(
            org.mtr.mapping.holder.ServerWorld serverWorld,
            BlockPos pos1, double angle1,
            BlockPos pos2, double angle2,
            RailType railType, boolean isOneWay, UUID uuid
    ) {
        final Position p1 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(pos1));
        final Position p2 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(pos2));
        // 复刻 Rail.getAngles 的朝向语义（端点1=离开方向、端点2=进入方向，按连线方向自动 ±180°），
        // 但跳过其内部的 Angle.fromAngle → getQuadrant 22.5° 快照：改用 AngleExtra 生成任意精度
        // Angle（幻影实例），使万向节点建出的轨道摆脱原版 16×22.5° 离散限制。
        // RailMath 几何计算只消费 angle.angleRadians/sin/cos 等连续值，任意角度完全支持。
        // 连线方向按「锚点 = 方块坐标 + 节点平移」算：节点平移是亚格量，用整数坐标会丢掉偏移。
        final double[] offset1 = readNodeOffset(serverWorld.data, pos1);
        final double[] offset2 = readNodeOffset(serverWorld.data, pos2);
        final double anchorDx = (p2.getX() + offset2[0]) - (p1.getX() + offset1[0]);
        final double anchorDz = (p2.getZ() + offset2[2]) - (p1.getZ() + offset1[2]);
        final double angleDifference = Math.toDegrees(Math.atan2(anchorDz, anchorDx));
        final double deg1 = normalizeDegrees(angle1 + (Angle.similarFacing((float) angleDifference, (float) angle1) ? 0 : 180));
        final double deg2 = normalizeDegrees(angle2 + (Angle.similarFacing((float) angleDifference, (float) angle2) ? 180 : 0));
        final Angle a1 = AngleExtra.fromDegrees(deg1);
        final Angle a2 = AngleExtra.fromDegrees(deg2);

        final Rail rail = buildRail(p1, a1, p2, a2, new ObjectArrayList<>(), railType.railShape, railType, isOneWay);
        if (rail == null || !rail.isValid()) {
            return false;
        }
        // 附加姿态（节点平移）：必须在 copy 之前写入，RailMixin 已在 Rail.copy 的 RETURN 上补姿态，
        // 所以复制出来的 styledRail 会自带；这里再对最终对象写一次，防止 SERVER_CACHE 内部走别的路径。
        final RailPoseExtra pose = readRailPose(serverWorld.data, pos1, pos2);
        RailPoseExtraHolder.apply(rail, pose);
        // 应用玩家最后使用的轨道样式：空样式会以"无模型"渲染（RenderRails 对空 styles 不绘制），
        // getRailWithLastStyles 会补上默认样式（CustomResourceLoader.DEFAULT_RAIL_ID），与原版
        // ItemRailModifier.createRail 行为一致。
        final Rail styledRail;
        if (uuid == null) {
            styledRail = Rail.copy(rail, ObjectArrayList.of(org.mtr.mod.client.CustomResourceLoader.DEFAULT_RAIL_ID));
        } else {
            styledRail = PacketUpdateLastRailStyles.SERVER_CACHE.getRailWithLastStyles(uuid, rail);
        }
        if (styledRail != null) {
            RailPoseExtraHolder.apply(styledRail, pose);
        }
        com.fangsu.Main.LOGGER.info("[NodeConnector] createAndSendRail {}->{} a1={} a2={} type={} oneWay={} rail={} valid={}", pos1, pos2, angle1, angle2, railType, isOneWay, styledRail, styledRail == null ? "n/a" : styledRail.isValid());
        PacketUpdateData.sendDirectlyToServerRail(serverWorld, styledRail);
        com.fangsu.Main.LOGGER.info("[NodeConnector] sent rail to server hexId={}", styledRail.getHexId());
        return true;
    }

    /**
     * 按连接器类型构建轨道（照抄原版 {@code ItemRailModifier.createRail} 的 TRAIN 分支）。
     * <ul>
     *   <li>PLATFORM/SIDING/TURN_BACK → 对应工厂方法（限速、站台/侧线/折返语义由 core 内部处理）</li>
     *   <li>其他 → {@link Rail#newRail}，单向时反向限速 0，RUNWAY 允许远程连接</li>
     * </ul>
     *
     * @param styles 轨道样式列表（空列表即可，发送前由调用方经 getRailWithLastStyles 补默认样式）
     */
    private static Rail buildRail(Position p1, Angle a1, Position p2, Angle a2, ObjectArrayList<String> styles, Rail.Shape shape, RailType railType, boolean isOneWay) {
        final boolean isPlatform = railType == RailType.PLATFORM;
        final boolean isSiding = railType == RailType.SIDING;
        final boolean canTurnBack = railType == RailType.TURN_BACK;
        if (isPlatform) {
            return Rail.newPlatformRail(p1, a1, p2, a2, shape, 0, styles, TransportMode.TRAIN);
        }
        if (isSiding) {
            return Rail.newSidingRail(p1, a1, p2, a2, shape, 0, styles, TransportMode.TRAIN);
        }
        if (canTurnBack) {
            return Rail.newTurnBackRail(p1, a1, p2, a2, shape, 0, styles, TransportMode.TRAIN);
        }
        return Rail.newRail(
                p1, a1, p2, a2,
                shape, 0, styles,
                isOneWay ? 0 : railType.speedLimit, railType.speedLimit,
                false, false, railType.canAccelerate,
                railType == RailType.RUNWAY, railType.hasSignal,
                TransportMode.TRAIN
        );
    }

    /**
     * 刷新重建一条轨道所需的属性（客户端从旧轨道读取，经网络包传到服务端）。
     */
    public record RailAttrs(
            long speedLimitAtNode,
            long speedLimitAtOther,
            Rail.Shape shape,
            boolean isPlatform,
            boolean isSiding,
            boolean canTurnBack,
            boolean canAccelerate,
            boolean canConnectRemotely,
            List<String> styles
    ) {
    }

    /**
     * 服务端：原地替换连接 nodePos 与 otherPos 的单条轨道（<b>不删除</b>，理由见方法末尾）。
     * <p>
     * nodePos 为万向节点且已绑定新方向 newDirection；otherPos 为另一端（万向节点或普通节点）。
     * 以新方向与另一端既有角度，按旧轨道属性（限速/单向/类型/样式）重建并原地替换旧轨道，
     * 保证角度调整后轨道外观与功能不丢失。
     * <p>
     * 另一端角度语义与 {@code com.fangsu.mixin.ItemNodeModifierBaseMixin#handleRailConnect} 一致：
     * 万向节点取绑定角度、普通节点 blockstate 角度即其绑定方向，均固定使用；
     * 固定角度组合在 RailMath 几何不成立（退化）时，若另一端是普通节点（无绑定意图），
     * 降级为该端取最大半径圆弧切向（与本端新方向平滑衔接），保证重建不静默失败。
     *
     * @param level        服务端世界
     * @param nodePos      万向节点位置（已绑定新方向）
     * @param newDirection 万向节点新方向（度）
     * @param otherPos     另一端位置
     * @param attrs        旧轨道属性（速度按端点位置对号入座，单向轨的 0 限速端跟随位置）
     * @param tiltCarry    客户端随刷新请求带来的「作者授权逐轨道超高」；{@code null} 或
     *                     {@link RailTiltCarry#hasRailTilt()} 为 false 时保持节点派生值
     * @return true = 已派发替换轨道；false = 所有候选几何都非法，<b>旧轨道原样保留</b>
     */
    public static boolean refreshNodeRail(Level level, BlockPos nodePos, double newDirection, BlockPos otherPos, RailAttrs attrs, RailTiltCarry tiltCarry) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) return false;
        final org.mtr.mapping.holder.ServerWorld serverWorld = new org.mtr.mapping.holder.ServerWorld(serverLevel);

        final Position p1 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(nodePos));
        final Position p2 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(otherPos));

        final ObjectArrayList<String> styles = new ObjectArrayList<>(attrs.styles());
        // 角度必须用「锚点 = 方块坐标 + 节点平移」计算：节点平移是亚格量，
        // 用整数方块坐标算出来的 near/far 判向会在偏移较大时翻转，重建出的轨道方向与用户绑定不符。
        // 平移现在由 NODE_REFRESH_RAIL 一并送达（见 ModNetwork.handleNodeRefreshRail），
        // 服务端 BE 已是最新值，这里读到的就是用户在界面上刚编辑的姿态。
        final double[] nodeOffset = readNodeOffset(level, nodePos);
        final double[] otherOffset = readNodeOffset(level, otherPos);
        final double anchorDx = (p2.getX() + otherOffset[0]) - (p1.getX() + nodeOffset[0]);
        final double anchorDz = (p2.getZ() + otherOffset[2]) - (p1.getZ() + nodeOffset[2]);
        final double angleDifference = Math.toDegrees(Math.atan2(anchorDz, anchorDx));
        final double deg1 = normalizeDegrees(newDirection + (Angle.similarFacing((float) angleDifference, (float) newDirection) ? 0 : 180));

        // 另一端角度：万向节点取绑定角度；普通节点 blockstate 角度即其绑定方向（与原版节点语义一致）
        final boolean otherIsMultiDirectionNode = isMultiDirectionNode(level, otherPos);
        final double otherAngle = getDirectionDegrees(level, otherPos);
        final double deg2 = normalizeDegrees(otherAngle + (Angle.similarFacing((float) angleDifference, (float) otherAngle) ? 180 : 0));

        // 节点派生姿态：平移 / 俯仰 / 节点翻滚 / 半轨距都按服务端方块实体里的当前值重算。
        // 注意它<b>不含</b>逐轨道超高（readRailPose 只产出节点派生数据，四点全为「未授权」）。
        RailPoseExtra pose = readRailPose(serverWorld.data, nodePos, otherPos);
        // 再合并客户端随刷新请求带来的「作者授权逐轨道超高」：
        // 只覆盖三点剖面与半轨距，上面刚派生的平移 / 俯仰 / 节点翻滚原样保留。
        // 未授权（tiltCarry 为 null 或 hasRailTilt 为 false）时不合并，本轨道完全跟随节点值 ——
        // 这正是「清除」之后应有的行为，因此「未授权」必须由显式标记表达，
        // 不能用 0/0/0 当哨兵（0/0/0 是「作者显式授权成水平」，要保持不倾斜）。
        if (tiltCarry != null) {
            pose = tiltCarry.mergeInto(pose);
        }

        // ---- 先构建并校验候选轨道，全部非法就原样保留旧轨道 ----
        // 旧实现是「先按 hexId 删旧轨、再试几何」：几何非法时旧轨已经没了，
        // 轨道凭空消失且无法恢复（日志只留一句 invalid geometry）。
        // 因此所有写操作（派发替换轨道）都必须排在候选校验通过之后。
        Rail candidate = buildRailForAngles(p1, p2, deg1, deg2, attrs, styles);
        if (candidate == null && !otherIsMultiDirectionNode) {
            // 另一端是普通节点（无绑定意图）时按旧行为降级：取最大半径圆弧切向与本端平滑衔接
            final double degraded = maxRadiusTangentAngle(nodePos, nodeOffset, newDirection, otherPos, otherOffset);
            final double deg2b = normalizeDegrees(degraded + (Angle.similarFacing((float) angleDifference, (float) degraded) ? 180 : 0));
            candidate = buildRailForAngles(p1, p2, deg1, deg2b, attrs, styles);
        }
        if (candidate == null) {
            com.fangsu.Main.LOGGER.warn("[NodeConnector] refreshNodeRail failed (invalid geometry) {}->{}: existing rail kept", nodePos, otherPos);
            return false;
        }
        // 附加姿态必须在派发前写入；写入后几何仍须有效，否则同样放弃
        RailPoseExtraHolder.apply(candidate, pose);
        if (!candidate.isValid()) {
            com.fangsu.Main.LOGGER.warn("[NodeConnector] refreshNodeRail failed (invalid geometry after pose) {}->{}: existing rail kept", nodePos, otherPos);
            return false;
        }

        // 候选轨道通过全部校验，此刻才真正替换旧轨道。
        // 这里**只发 UPDATE_DATA，刻意不再先发 DELETE_DATA**：同一个 hexId 的轨道在 core 的
        // UpdateDataRequest.update 里是「先 remove 旧对象、再 add 新对象」的原地替换
        // （javap 4.0.5 权威 jar：lambda$update$6 -> update(...) -> ObjectSet.remove(existing) + add(new)），
        // 所以删除纯属多余，却带来一个致命副作用：
        //   DeleteDataRequest.delete 只把「删除后 positionsToRail 里已无轨道」的位置回报给 resetRailNode，
        //   于是**只有一条轨道**的节点会在删旧轨的那一刻被误判为「已无轨道」而被复位 connected；
        //   更糟的是这条回报是延迟回调（Init.sendMessageC2S -> minecraftServer.execute），
        //   落地时间晚于 ModNetwork.handleNodeRefreshRail 里的 markConnected，
        //   复位反而覆盖了刚写好的「已连接」。
        //   现象即：单轨节点平移/旋转后模型变回未连接、界面「旋转绑定」被解锁。
        // 不删就没有这个误报，也不再有「旧轨已删、新轨未到」的瞬时无轨窗口。
        PacketUpdateData.sendDirectlyToServerRail(serverWorld, candidate);
        com.fangsu.Main.LOGGER.info("[NodeConnector] refreshed rail {}->{} hexId={}", nodePos, otherPos, candidate.getHexId());
        return true;
    }

    /**
     * 按两角度构建一条候选轨道，<b>不派发、不删除任何东西</b>。
     * <p>
     * 服务端重建（{@link #refreshNodeRail}）与客户端几何预检（{@link #hasValidGeometry}）
     * 共用这段构建逻辑，保证界面看到的「是否合法」与服务端实际会走的分支一致。
     *
     * @return 合法轨道；{@code null} = RailMath 几何不成立
     */
    @Nullable
    private static Rail buildRailForAngles(
            Position p1, Position p2,
            double deg1, double deg2,
            RailAttrs attrs, ObjectArrayList<String> styles
    ) {
        final Angle a1 = AngleExtra.fromDegrees(deg1);
        final Angle a2 = AngleExtra.fromDegrees(deg2);
        final Rail rail;
        if (attrs.isPlatform()) {
            rail = Rail.newPlatformRail(p1, a1, p2, a2, attrs.shape(), 0, styles, TransportMode.TRAIN);
        } else if (attrs.isSiding()) {
            rail = Rail.newSidingRail(p1, a1, p2, a2, attrs.shape(), 0, styles, TransportMode.TRAIN);
        } else if (attrs.canTurnBack()) {
            rail = Rail.newTurnBackRail(p1, a1, p2, a2, attrs.shape(), 0, styles, TransportMode.TRAIN);
        } else {
            // 普通轨：限速按端点位置对号入座（单向轨 0 限速端跟随位置），hasSignal 由 RUNWAY 标志推断
            rail = Rail.newRail(
                    p1, a1, p2, a2,
                    attrs.shape(), 0, styles,
                    attrs.speedLimitAtNode(), attrs.speedLimitAtOther(),
                    false, false, attrs.canAccelerate(),
                    attrs.canConnectRemotely(), !attrs.canConnectRemotely(),
                    TransportMode.TRAIN
            );
        }
        return rail != null && rail.isValid() ? rail : null;
    }

    // ==================== 客户端几何预检（界面红字警告） ====================

    /**
     * 客户端安全、<b>不发包也不修改世界</b>的几何预检：按服务端 {@link #refreshNodeRail} 相同的顺序
     * 构造候选轨道，只回答「当前编辑的姿态 + 方向能否产出至少一条合法轨道」。
     * <p>
     * 实现参考 {@code RenderRailsMixin} 的 ghost rail 预览：只用 {@code Init.blockPosToPosition}
     * 与 {@code Rail.newRail(...)} 构造核心对象，不接触任何服务端数据、不派发任何包。
     * 配置界面据此显示红色警告，并在姿态非法时<b>跳过</b>重建请求，避免触发一次注定失败的删除+重建。
     *
     * @param level        世界（只读：读另一端节点的平移与方向）
     * @param nodePos      万向节点位置
     * @param nodeOffset   界面里正在编辑的节点平移 {@code {x, y, z}}
     * @param newDirection 界面里正在编辑的方向（度）
     * @param otherPos     另一端位置
     * @param otherAngle   另一端当前方向（度）
     * @param shape        轨道形状（应与服务端重建时使用的形状一致）
     */
    public static boolean hasValidGeometry(Level level, BlockPos nodePos, double[] nodeOffset,
                                           double newDirection, BlockPos otherPos, double otherAngle,
                                           Rail.Shape shape) {
        return hasValidGeometry(level, nodePos, nodeOffset, newDirection, otherPos, otherAngle, attrsForShape(shape));
    }

    /**
     * {@link #hasValidGeometry(Level, BlockPos, double[], double, BlockPos, double, Rail.Shape)} 的
     * 完整属性版本：形状之外的站台/侧线/折返标记也一并按候选属性构建（与服务端分支一一对应）。
     */
    public static boolean hasValidGeometry(Level level, BlockPos nodePos, double[] nodeOffset,
                                           double newDirection, BlockPos otherPos, double otherAngle,
                                           RailAttrs attrs) {
        if (level == null || nodePos == null || otherPos == null || nodeOffset == null) {
            // 参数不全时按「有效」处理：宁可不显示警告，也不要误报红色
            return true;
        }
        final Position p1 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(nodePos));
        final Position p2 = Init.blockPosToPosition(new org.mtr.mapping.holder.BlockPos(otherPos));
        final double[] otherOffset = readNodeOffset(level, otherPos);
        final double anchorDx = (p2.getX() + otherOffset[0]) - (p1.getX() + nodeOffset[0]);
        final double anchorDz = (p2.getZ() + otherOffset[2]) - (p1.getZ() + nodeOffset[2]);
        final double angleDifference = Math.toDegrees(Math.atan2(anchorDz, anchorDx));
        final double deg1 = normalizeDegrees(newDirection + (Angle.similarFacing((float) angleDifference, (float) newDirection) ? 0 : 180));
        final double deg2 = normalizeDegrees(otherAngle + (Angle.similarFacing((float) angleDifference, (float) otherAngle) ? 180 : 0));

        final ObjectArrayList<String> styles = new ObjectArrayList<>();
        if (buildRailForAngles(p1, p2, deg1, deg2, attrs, styles) != null) {
            return true;
        }
        // 与服务端一致：另一端是普通节点时允许降级为「最大半径圆弧切向」
        if (!isMultiDirectionNode(level, otherPos)) {
            final double degraded = maxRadiusTangentAngle(nodePos, nodeOffset, newDirection, otherPos, otherOffset);
            final double deg2b = normalizeDegrees(degraded + (Angle.similarFacing((float) angleDifference, (float) degraded) ? 180 : 0));
            return buildRailForAngles(p1, p2, deg1, deg2b, attrs, styles) != null;
        }
        return false;
    }

    /**
     * 只关心形状时的候选属性：站台/侧线/折返标记全 false，几何计算与这些标记无关。
     * <p>
     * <b>限速必须给正值，不能给 0</b>。MTR 4.0.5 的 {@code Rail.isValid()} 并不只看几何，它的实际语义是
     * （javap 真实 jar 逐指令核对）：
     * <pre>
     * return (speedLimit1 &gt; 0 || speedLimit2 &gt; 0) &amp;&amp; railMath.isValid() &amp;&amp; !(isPlatform &amp;&amp; isSiding);
     * </pre>
     * 也就是说「两端限速都为 0 的轨道一律被判为不合法」。而 {@code RailMath.isValid()} 是包私有、
     * {@code com.fangsu.*} 无法调用，所以客户端预检绕不开 {@code Rail.isValid()}。
     * 早先这里给的是 {@code 0L, 0L}，导致预检对<b>任何</b>姿态都返回不合法 ——
     * 界面因此恒显红字并跳过重建（实测现象：连刚绑定好的合法方向也会红字且不重建）。
     * <p>
     * 用正限速把 isValid() 的限速条件解除后，剩下的就是
     * {@code railMath.isValid() &amp;&amp; !(isPlatform &amp;&amp; isSiding)}；而本方法两个标记都是 false，
     * 于是判据恰好退化为「纯几何是否成立」——这正是预检想要的。
     * （真实轨道不会是 0/0：平台/侧线 80/80 与 80/40，折返 80/80，普通轨取 RailType 限速，
     *   单向轨也总有一端为正，所以服务端 {@code refreshNodeRail} 用旧轨真实属性做校验不受此影响。）
     */
    private static RailAttrs attrsForShape(Rail.Shape shape) {
        return new RailAttrs(1L, 1L, shape, false, false, false, false, false, List.of());
    }

    /** 该位置是否为 FangSu 万向节点（服务端降级分支与客户端预检共用同一判据）。 */
    private static boolean isMultiDirectionNode(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof com.fangsu.blocks.BlockMultiDirectionNode;
    }
}
