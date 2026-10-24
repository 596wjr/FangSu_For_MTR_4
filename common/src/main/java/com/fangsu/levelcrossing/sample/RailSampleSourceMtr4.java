package com.fangsu.levelcrossing.sample;

import com.fangsu.Main;
import com.fangsu.customItem.NteRailManager;
import com.fangsu.customItem.NteRailManager.NteRail;
import com.fangsu.mappings.rail.RailPoseExtra;
import com.fangsu.mtr.rail.FangSuRailMath;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.mod.client.MinecraftClientData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MTR4 版轨道采样源：先给客户端的 {@code positionsToRail} 打一份浅快照，再在锁外做粗筛与采样。
 * <p>
 * <b>为什么用 {@code positionsToRail} 而不是 {@code railWrapperList}</b>：两者都挂在
 * {@link MinecraftClientData} 上，但 {@code RailWrapper} 只暴露 {@code getRail()}，而 MTR4 的
 * 两个端点 {@code Rail.getPosition1()/getPosition2()} 是 {@code protected abstract}
 * （{@code TwoPositionsBase.java:30-32}），从 {@code com.fangsu} 包根本调不到。唯一能从外部拿到
 * 端点的是 {@code positionsToRail} 的<b>键</b>：它是"起点 → (终点 → 轨道)"的双向表，
 * 一次遍历同时给出两端坐标和轨道本体。AABB 也不缺——{@code Rail.railMath} 自带
 * {@code minX/minY/minZ/maxX/maxZ}（{@code RailMath.java:23-28}），比 {@code RailWrapper}
 * 缓存的 {@code startVector/endVector} 更直接。
 * <p>
 * <b>并发</b>：整表由数据包线程重建（{@code PacketUpdateVehiclesLifts} 在包处理线程调
 * {@code MinecraftClientData.sync()}，后者遍历 {@code positionsToRail} 重建 wrap 表）。
 * 因此这里只在 {@code synchronized} 内做"读键 + 取 Rail 引用"这种最便宜的浅拷贝，
 * AABB 粗筛与 {@code railMath} 采样全部放到锁外：{@code Rail} 的几何字段（含
 * {@code railMath}）都是 {@code final}，轨道被更新时 MTR4 换的是整张表里的<b>引用</b>，
 * 旧对象可以安全地在锁外读完。锁本身只用来串行化我们自己的读者——写者不持锁，
 * 所以仍可能在拷贝途撞上迭代器失效，那种情况按 MTR3 的做法降级为"用已收集到的部分"。
 */
public final class RailSampleSourceMtr4 implements RailSampleSource {

    private static final RailSampleSourceMtr4 INSTANCE = new RailSampleSourceMtr4();

    /** AABB 粗筛的额外宽容（格）：粗筛只看包围盒，精判交给 {@link LevelCrossingSampler} 的走廊裁切。 */
    private static final double PREFILTER_SLACK = 0.5D;

    /**
     * 认不出具体轨道型号（或原版轨道）时使用的 id。
     * <p>
     * MTR4 树 {@code assets/fangsu/rail_gauge.json} 里这一条是<b>实测值</b>，来源：
     * 从构建 jar 解出的 {@code assets/mtr/models/rail/rail.obj}（72 顶点）量得
     * Y ∈ [0, 0.2643]（轨顶真实高度）、枕木端 X ∈ [-1.116, 1.116]、钢轨头横向约 ±0.760…0.860；
     * 对应的自定义轨道资源是 {@code default_3d}（modelResource={@code mtr:models/rail/rail.obj}，
     * repeatInterval=0.5，见 jar 内 {@code assets/mtr/mtr_custom_resources.json}）。
     * <p>
     * 换 MTR 版本或换资源包后这组数必须重测；MTR3 树不用这组值（那边是 NTE 轨道，
     * railTopY 由模型包围盒自动取，所以 rails["mtr:default"] 仍是平面贴图的 {@code 0.0625+0.01}）。
     */
    private static final String DEFAULT_RAIL_ID = "mtr:default";

    /** MTR4 原生轨道样式 id（{@code CustomResourceLoader.DEFAULT_RAIL_ID}），等价于"没有自定义轨道模型"。 */
    private static final String MTR_DEFAULT_STYLE = "default";

    public static RailSampleSourceMtr4 getInstance() {
        return INSTANCE;
    }

    private RailSampleSourceMtr4() {
    }

    @Override
    public List<RailPath> nearby(Level level, double centerX, double centerZ, double radius) {
        if (!(radius > 0)) {
            return List.of();
        }
        MinecraftClientData clientData = MinecraftClientData.getInstance();
        if (clientData == null) {
            return List.of();
        }

        List<RailSnapshot> snapshot = copySnapshot(clientData);
        List<RailPath> result = new ArrayList<>(snapshot.size());
        for (RailSnapshot entry : snapshot) {
            if (!maybeNear(entry.rail().railMath, centerX, centerZ, radius)) {
                continue;
            }
            result.add(new RailPath(resolveRailId(entry.rail()),
                    new Mtr4Geometry(entry.rail(), entry.pos1(), entry.pos2())));
        }
        return result;
    }

    // ==================== 快照 ====================

    /**
     * 锁内浅拷贝：只读键、取 {@link Rail} 引用，不碰任何几何。
     *
     * @return 按轨道去重后的快照（{@code positionsToRail} 是双向表，一条轨道会出现两次）
     */
    private static List<RailSnapshot> copySnapshot(MinecraftClientData clientData) {
        // 去重键用 rail 自己的 hexId：它由两端坐标规范化后生成（TwoPositionsBase.getHexId），
        // (a,b) 与 (b,a) 必然落到同一个键上，正好抵消双向表的重复。
        Map<String, RailSnapshot> byRail = new HashMap<>();
        try {
            synchronized (clientData.positionsToRail) {
                for (Map.Entry<Position, Object2ObjectOpenHashMap<Position, Rail>> outer
                        : clientData.positionsToRail.entrySet()) {
                    Position start = outer.getKey();
                    Object2ObjectOpenHashMap<Position, Rail> inner = outer.getValue();
                    if (start == null || inner == null) {
                        continue;
                    }
                    for (Map.Entry<Position, Rail> entry : inner.entrySet()) {
                        Position end = entry.getKey();
                        Rail rail = entry.getValue();
                        if (end == null || rail == null) {
                            continue;
                        }
                        byRail.putIfAbsent(rail.getHexId(), new RailSnapshot(toBlockPos(start), toBlockPos(end), rail));
                    }
                }
            }
        } catch (RuntimeException e) {
            // 极少见：数据包线程正在整表重建，锁内仍可能撞上迭代器失效。已收集到的先用着。
            Main.LOGGER.warn("[FangSu] Level crossing: rail table changed while taking snapshot, using partial result", e);
        }
        return new ArrayList<>(byRail.values());
    }

    private static BlockPos toBlockPos(Position position) {
        return new BlockPos((int) position.getX(), (int) position.getY(), (int) position.getZ());
    }

    // ==================== 粗筛 ====================

    /**
     * 用 {@code railMath} 的包围盒做粗筛：搜索圆的外接正方形与包围盒（外扩 {@link #PREFILTER_SLACK}）相交。
     * <p>
     * MTR3 那份要自己用 8 段折线近似（{@code mtr.data.Rail} 没有包围盒），MTR4 的
     * {@code RailMath} 在构造时就把轨道采样了一遍并算好 {@code min/max}（{@code RailMath.java:308-313}），
     * 直接白用。包围盒是中心线的紧包围盒，圆半径整体外扩后不会漏掉任何真正相交的轨道。
     */
    private static boolean maybeNear(RailMath railMath, double centerX, double centerZ, double radius) {
        if (railMath == null) {
            return false;
        }
        double limit = radius + PREFILTER_SLACK;
        return centerX >= railMath.minX - limit && centerX <= railMath.maxX + limit
                && centerZ >= railMath.minZ - limit && centerZ <= railMath.maxZ + limit;
    }

    // ==================== 轨道 id ====================

    /**
     * 解析用于查几何参数表的轨道 id。
     * <p>
     * MTR4 的 rail schema 里<b>没有</b> railId 字段，生成类 {@code RailSchema} 也没有
     * {@code getRailId()} / {@code railId}；{@code Rail} 唯一能拿到的字符串标识是
     * {@code getHexId()}，但那是"两端坐标的十六进制串"，逐条轨道唯一，几何参数表里永远查不到，
     * 拿它当 id 只会让 {@code LevelCrossingGaugeTable} 按轨道条数刷警告。所以这里不走 hexId。
     * <p>
     * 能表达"这条轨道用了哪个模型"的是 {@code Rail.getStyles()}（MTR4 的自定义轨道样式 id，
     * 原版轨道是 {@code "default"}）。方速的轨道表按 NteRail id 索引，因此把样式折回
     * {@code NteRailManager}：认得才用，认不得就一律回落 {@link #DEFAULT_RAIL_ID}，
     * 交给 {@code rail_gauge.json} 的 defaults，绝不送一个查不到的 id 去刷警告。
     */
    private static String resolveRailId(Rail rail) {
        // 刻意不做按 Rail 的缓存：MTR4 每次同步都会重建 positionsToRail 并替换 Rail 引用，
        // 以 Rail 为键的 map 会把旧对象一直钉在堆里。只有命中判定的少数几条会走到这里。
        String style = firstStyle(rail);
        if (style == null || MTR_DEFAULT_STYLE.equals(style)) {
            return DEFAULT_RAIL_ID;
        }
        NteRailManager manager = NteRailManager.getInstance();
        if (manager.getRail(style) != null) {
            return style;
        }
        for (NteRail candidate : manager.getRails()) {
            if (style.equals(candidate.model())) {
                return candidate.id();
            }
        }
        return DEFAULT_RAIL_ID;
    }

    private static String firstStyle(Rail rail) {
        for (String style : rail.getStyles()) {
            if (style != null && !style.isEmpty()) {
                return style;
            }
        }
        return null;
    }

    // ==================== 几何句柄 ====================

    /**
     * 端点在这里只是"给下游读节点姿态"用的落地坐标，几何本身全部走 {@code railMath}。
     */
    private record Mtr4Geometry(Rail rail, BlockPos pos1, BlockPos pos2) implements RailGeometry {

        @Override
        public double length() {
            RailMath railMath = rail.railMath;
            if (railMath == null) {
                return 0;
            }
            double length = railMath.getLength();
            return Double.isFinite(length) ? length : 0;
        }

        @Override
        public Vec3 position(double t) {
            RailMath railMath = rail.railMath;
            if (railMath == null) {
                return Vec3.ZERO;
            }
            double length = length();
            double clamped = Math.max(0, Math.min(length, t));
            // getPosition 的每个参数都是"沿轨道走过的距离"，reverse=false 表示从 railMath 自己的起点量起
            Vector vector = railMath.getPosition(clamped, false);
            return vector == null ? Vec3.ZERO : new Vec3(vector.x(), vector.y(), vector.z());
        }

        /**
         * 轨道超高（度）＝附加姿态两端滚转的平均值。
         * <p>
         * <b>为什么不用 {@code RailMath} 的 tilt 接口</b>：真实构建依赖（MTR 4.0.5+1.20.1）里的
         * {@code org.mtr.core.data.RailMath} 只有 {@code getPosition(double,boolean)} / {@code getLength()}，
         * <b>没有</b> {@code getTiltAngle}（已用 javap 核实）；{@code mtr4/} 参考源码树是更高版本，
         * 不能照着写。
         * <p>
         * <b>实际取法</b>：方速自己的几何主钩子（{@code RailMixin}）在附加姿态影响几何时会把
         * {@code rail.railMath} 换成 {@link FangSuRailMath}，它把姿态原样带在身上
         * （{@link FangSuRailMath#getFangSuPose()}，{@code FangSuRailMath.java:156}）。
         * 于是超高 = {@code (roll1Degrees + roll2Degrees) / 2}，单位为<b>度</b>，
         * 字段见 {@code RailPoseExtra.java:75-76}。
         * <p>
         * <b>与 MTR3 同源</b>：MTR3 那份走 {@code NodeConnector.readRailPose} 取两端节点滚转再平均，
         * 语义完全相同（同一批 {@code roll1Degrees/roll2Degrees}、同样的单位与符号），所以两树不会
         * 出现超高反号。plain {@code RailMath}（原版轨道，或姿态不影响几何）返回 0，与 MTR3 在
         * {@code readRailPose} 给出全零时的行为一致。
         */
        @Override
        public double tiltDegrees() {
            RailMath math = rail.railMath;
            if (math instanceof FangSuRailMath fangSuMath) {
                RailPoseExtra pose = fangSuMath.getFangSuPose();
                if (pose != null) {
                    double roll = (pose.roll1Degrees + pose.roll2Degrees) / 2.0D;
                    if (Double.isFinite(roll)) {
                        return roll;
                    }
                }
            }
            return 0.0D;
        }
    }

    /** 锁外使用的浅快照：一条轨道 + 它的两个端点方块坐标。 */
    private record RailSnapshot(BlockPos pos1, BlockPos pos2, Rail rail) {
    }
}
