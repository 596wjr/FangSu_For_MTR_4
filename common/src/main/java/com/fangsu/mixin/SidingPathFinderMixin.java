package com.fangsu.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.core.tool.Angle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 放宽寻路在节点处的角度匹配，使万向节点的小角度转弯布局可以寻路。
 * <p>
 * MTR 原版 {@code SidingPathFinder.getConnections} 的 forEach lambda 要求列车在节点处
 * 直行：{@code node.angle == rail.getStartAngle(node.position)}（引用比较，javap 确认
 * 字节码为 {@code if_acmpeq}）。直行语义 = 两者数值相等（模 360）：普通节点 blockstate
 * 角度 flip 后恒精确直行，且 {@code Angle} 枚举本身单例，引用比较天然成立；万向节点
 * （任意角度）建出的轨道在拐弯布局下节点处角度与直行偏差非 0，且幻影 {@code Angle}
 * 实例非单例，原版寻路必然判不可达。
 * <p>
 * 注入策略（lambda 方法体字节码，javap 已验证）——三个注入点配合，全部使用 public
 * 类型签名（不引用 MTR 的 protected 嵌套类 {@code PositionAndAngle}，mixin 类与目标类
 * 同包在运行时会被 Mixin 拒绝加载；{@code @Inject}/{@code @Overwrite} 的严格描述符
 * 匹配又无法跨包书写该类型）：
 * <ol>
 *   <li>{@code @WrapOperation}（偏移 5 的 {@code getSpeedLimitMetersPerMillisecond}，
 *       参数 Rail/Position 均为 public）：算速限的同时把 {@code rail.getStartAngle(position)}
 *       的实例缓存到 {@link #FANGSU_CACHED_RAIL_ANGLE}（speedLimit &lt;= 0 时清空）</li>
 *   <li>{@code @ModifyExpressionValue}（比较左操作数，lambda 内第二次 {@code access$200}
 *       调用，无 target + {@code ordinal = 3}，参见方法注释里的调用序列表）：若 node.angle
 *       与缓存实例最短角差 ≤ {@link #MAX_TURN_DEVIATION}，把 node.angle 替换为缓存实例
 *       （同引用）。不能写 {@code access$200} 进 target（方法名含 {@code $}，Mixin AP
 *       编译期拒绝），lambda 内又无 {@code getfield angle}（FIELD 定位运行时扫 0），
 *       只能省略 target 用 ordinal 计数</li>
 *   <li>{@code @ModifyExpressionValue}（偏移 33 的 {@code getStartAngle}，比较右操作数，
 *       {@code ordinal = 0} 排除偏移 58 处构造 nextAngle 的调用）：返回缓存实例
 *       （同引用）→ {@code if_acmpeq} 两侧同引用，比较成立</li>
 * </ol>
 * 结果：节点处 ≤22.5° 的小角度转弯可寻路（掉头 180° 仍需 {@code canTurnBack()} 折返轨，
 * 语义不变）。不匹配时返回原值，比较仍为 false，与原版一致；{@code node.angle == null}
 * 短路（偏移 17）与 {@code canTurnBack()}（偏移 40）分支不受影响。缓存用 ThreadLocal
 * 做线程隔离：自 278e1ba（异步寻路）起，车厂主路径寻路在独立 worker 线程执行
 * （{@link com.fangsu.utils.DepotPathGenerationManager}），与模拟线程的侧线寻路
 * （{@code Siding.tick} 原版节流路径）并发调用本 lambda——若用静态字段，对方线程的
 * getConnections 会覆盖缓存，注入点 3 无条件返回被污染的缓存实例使 {@code ==} 比较
 * 恒 false，连接被剔除、寻路漏边找不到路径（时序敏感，时好时坏）。ThreadLocal 后
 * 两路寻路各自缓存，且每个 lambda 调用都先经过注入点 1 重算（set），无残留问题。
 * <p>
 * 注意（升级 MTR 时需 javap 复核）：lambda 合成方法名 {@code lambda$getConnections$0}、
 * {@code getfield angle}/{@code getStartAngle}/{@code getSpeedLimitMetersPerMillisecond}
 * 的 target 字符串、方法内调用顺序（{@code ordinal}）；{@code defaultRequire: 1} 下
 * 任何一项失配都会崩溃。
 */
@Mixin(value = SidingPathFinder.class, remap = false)
public abstract class SidingPathFinderMixin {

    /** 节点处允许的最大转弯角度：与"直行"（数值相等）的最短角差上限（度）。 */
    private static final float MAX_TURN_DEVIATION = 22.5f;

    /**
     * 预计算的当前轨在节点处的角度实例：注入点 1 写入，比较两侧注入点复用同一实例使 == 成立。
     * 必须按线程隔离（见类注释）：worker 线程（车厂主路径）与模拟线程（侧线寻路）并发
     * 执行同一 lambda，静态字段会被对方线程的 getConnections 覆盖。
     */
    private static final ThreadLocal<Angle> FANGSU_CACHED_RAIL_ANGLE = new ThreadLocal<>();

    /**
     * 角度匹配：同一实例（枚举同值 / 幻影缓存同键）直接通过；否则比较最短角差
     * （幻影实例 angleDegrees 为 [0,360)、枚举为 [-180,180)，统一按差值模 360 计算，
     * 两种实例混合无需特判）。
     */
    private static boolean fangsu$anglesMatch(Angle a, Angle b) {
        if (a == b) {
            return true;
        }
        final float diff = Math.abs(a.angleDegrees - b.angleDegrees) % 360f;
        return Math.min(diff, 360f - diff) <= MAX_TURN_DEVIATION;
    }

    /**
     * 注入点 1（lambda 开头，getSpeedLimit 调用处）：算速限并预缓存当前轨在节点处的
     * 角度实例。speedLimit &lt;= 0 时清空缓存（此时原逻辑直接 return，后续注入点退化为
     * 返回原值，语义不变）。
     */
    @WrapOperation(
            method = "lambda$getConnections$0",
            at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Rail;getSpeedLimitMetersPerMillisecond(Lorg/mtr/core/data/Position;)D")
    )
    private static double fangsu$precomputeRailAngle(Rail rail, Position position, Operation<Double> original) {
        final double speedLimit = original.call(rail, position);
        FANGSU_CACHED_RAIL_ANGLE.set(speedLimit > 0 ? rail.getStartAngle(position) : null);
        return speedLimit;
    }

    /**
     * 注入点 2（比较左操作数，第二次 {@code access$200}）：偏差 ≤ MAX_TURN_DEVIATION
     * 时把 node.angle 替换为缓存实例——与右操作数（注入点 3 的同一实例）同引用，== 成立。
     * <p>
     * 定位方式（javap 4.0.5 实测）：lambda 读取 node.angle 是 synthetic 方法
     * {@code access$200}（INVOKESTATIC），方法名含 {@code $} 无法写进 {@code @At} target
     * ——Mixin 注解处理器（编译期）拒绝（"Invalid name: access$200"），而 lambda 内也
     * 不存在 {@code getfield angle}（FIELD 定位运行时扫描 0 目标）。故省略 target，用
     * ordinal 按「方法内所有 INVOKE 指令」计数定位（lambda 内调用顺序）：
     * <pre>
     * ordinal 0: access$100            (offset 2,  node.position 给 getSpeedLimit)
     * ordinal 1: getSpeedLimitMetersPerMillisecond  (offset 5,  注入点 1)
     * ordinal 2: access$200            (offset 18, ifnull 判起点，保持原语义)
     * ordinal 3: access$200            (offset 25, 本注入点 = 比较左操作数)
     * ordinal 4: access$100            (offset 30)
     * ordinal 5: getStartAngle         (offset 33, 比较右操作数，注入点 3)
     * ordinal 6: canTurnBack           (offset 40)
     * </pre>
     * 依赖完整调用序列，升级 MTR 时需按上述列表复核。
     */
    @ModifyExpressionValue(
            method = "lambda$getConnections$0",
            at = @At(value = "INVOKE", ordinal = 3)
    )
    private static Angle fangsu$matchNodeAngle(Angle nodeAngle) {
        final Angle railAngle = FANGSU_CACHED_RAIL_ANGLE.get();
        if (railAngle != null && fangsu$anglesMatch(nodeAngle, railAngle)) {
            return railAngle;
        }
        return nodeAngle;
    }

    /**
     * 注入点 3（比较右操作数，getStartAngle 调用处）：返回缓存实例（与注入点 2 写入的
     * 左侧同引用）。{@code ordinal = 0} 只命中比较处的调用，偏移 58 处构造 nextAngle
     * 的调用不受影响。未预计算（speedLimit&lt;=0）时返回原值。
     */
    @ModifyExpressionValue(
            method = "lambda$getConnections$0",
            at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Rail;getStartAngle(Lorg/mtr/core/data/Position;)Lorg/mtr/core/tool/Angle;", ordinal = 0)
    )
    private static Angle fangsu$reuseCachedRailAngle(Angle original) {
        final Angle cached = FANGSU_CACHED_RAIL_ANGLE.get();
        return cached != null ? cached : original;
    }
}
