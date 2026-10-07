package com.fangsu.mixin;

import com.fangsu.mappings.rail.RailPoseExtra;
import com.fangsu.mtr.rail.FangSuRailMath;
import com.fangsu.mtr.rail.RailPoseExtraHolder;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 {@code org.mtr.core.data.Rail} 使用 FangSu 的几何内核（MTR 4.0.5）。
 * <p>
 * 覆盖三件事：
 * <ol>
 *   <li><b>几何安装</b>：主钩子是构造器里 {@code NEW RailMath} 的 {@code @Redirect}，
 *       附加姿态非默认时替换成 {@link FangSuRailMath}。4.0.5 的 {@code Rail} 有两个构造器
 *       （私有全参构造 + {@code ReaderBase} 反序列化构造），每个里面都有 <b>两处</b>
 *       {@code NEW RailMath}（因为 {@code reversePositions} 会把两端对调），
 *       所以用 {@code method = "<init>"} 一次覆盖全部 4 处。</li>
 *   <li><b>兜底</b>：{@code ReaderBase} 构造器的 {@code @Return} 再确认一次。
 *       {@code @Redirect} 的 {@code require = 0} 意味着注入点解析失败时 Mixin 会静默跳过
 *       （而不是像默认 {@code require = 1} 那样直接崩游戏），此时由兜底重建几何。
 *       两条路都成功时用 {@code instanceof FangSuRailMath} 去重，不会重复安装。</li>
 *   <li><b>copy 保姿态</b>：{@code Rail.copy(Rail, Shape, double)} 与
 *       {@code Rail.copy(Rail, ObjectArrayList)} 都是 {@code new Rail(...)}，
 *       会丢掉附加姿态。{@code MultiDirectionNodeConfigScreen} 改形状/半径/样式正是走这两条路，
 *       不补的话用户一改形状平移就"弹回去"。</li>
 * </ol>
 * <b>硬性安全属性</b>：姿态为默认（{@link RailPoseExtra#DEFAULT}）时，
 * {@code rail.railMath.getClass() == RailMath.class} 必须成立 —— 见
 * {@link #rebuildRailMath()} 与 {@link #fangsu$redirectNewRailMath}，
 * 两条路径在默认姿态下都返回 MTR 自己的 {@code RailMath} 实例，原版轨道逐位一致。
 */
@Mixin(value = Rail.class, remap = false)
public abstract class RailMixin {

    /** MTR 的几何对象；{@code public final}，用 {@code @Mutable} 打开写入。 */
    @Shadow(remap = false) @Final @Mutable
    public RailMath railMath;

    // ---- 端点几何信息不再在这里影子 ----
    // position1/position2/angle1/angle2/shape/verticalRadius 声明在父类
    // org.mtr.core.generated.data.RailSchema 上，**不在**本 mixin 的目标 Rail 上。
    // 把 @Shadow 打在这里会被 Mixin 注解处理器报
    //     Cannot find target for @Shadow field in org.mtr.core.data.Rail
    // 且运行期存在解析不到的风险。改为：影子放在字段真正的声明类上（RailSchemaMixin），
    // 再通过 RailPoseExtraHolder 的 fangsu$getXxx() 访问器取值。

    // ==================== 1. 几何安装（主钩子） ====================

    /**
     * 把构造器里的 {@code new RailMath(positionA, angleA, positionB, angleB, shape, verticalRadius)}
     * 重定向到"默认姿态用原生、非默认姿态用内核"的工厂。
     * <p>
     * 端点判断：4.0.5 的 {@code reversePositions = position1.compareTo(position2) > 0}，
     * 为真时构造器传的是 {@code (position2, position1)}。这里直接用
     * {@code this.position1.equals(第一个入参)} 判断，天然覆盖两条分支，不必影子 {@code reversePositions}。
     * <p>
     * {@code require = 0}：注入点解析失败时不崩游戏，交给 {@link #fangsu$rebuildAfterDeserialize} 兜底。
     */
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "NEW",
                    target = "(Lorg/mtr/core/data/Position;Lorg/mtr/core/tool/Angle;Lorg/mtr/core/data/Position;Lorg/mtr/core/tool/Angle;Lorg/mtr/core/data/Rail$Shape;D)Lorg/mtr/core/data/RailMath;"
            ),
            require = 0,
            remap = false
    )
    private RailMath fangsu$redirectNewRailMath(
            Position firstPosition, Angle firstAngle,
            Position secondPosition, Angle secondAngle,
            Rail.Shape shape, double verticalRadius
    ) {
        final RailPoseExtraHolder holder = (RailPoseExtraHolder) (Object) this;
        final RailPoseExtra pose = holder.getFangSuPose();
        if (!pose.affectsGeometry()) {
            // 默认姿态 ⇒ 必须是 MTR 原生实例（硬性安全属性）
            return new RailMath(firstPosition, firstAngle, secondPosition, secondAngle, shape, verticalRadius);
        }
        final boolean firstIsPosition1 = holder.fangsu$getPosition1().equals(firstPosition);
        // 一次性 INFO 明确"主钩子生效"，之后逐条降级为 debug，避免每次数据同步刷屏
        if (FangSuRailMath.markFirstInstallLogged()) {
            com.fangsu.Main.LOGGER.info("[RailPose] 几何主钩子生效：NEW-Redirect 已在 Rail 构造器中安装 FangSuRailMath");
        }
        com.fangsu.Main.debug("[RailPose] installed FangSuRailMath {} -> {}", firstPosition, secondPosition);
        return new FangSuRailMath(
                firstPosition, firstAngle, secondPosition, secondAngle,
                shape, verticalRadius, pose, firstIsPosition1
        );
    }

    /**
     * 兜底：反序列化构造器结束（此时 {@code RailSchema(ReaderBase)} 已读完附加姿态，
     * 且 {@code railMath} 已由构造器装好）后再确认一次几何。
     */
    @Inject(method = "<init>(Lorg/mtr/core/serializer/ReaderBase;)V", at = @At("RETURN"), require = 0, remap = false)
    private void fangsu$rebuildAfterDeserialize(ReaderBase readerBase, CallbackInfo ci) {
        fangsu$rebuildIfNeeded();
    }

    /** 姿态非默认、但当前装的还是 MTR 原生 {@code RailMath}（说明 NEW-redirect 没生效）时重建。 */
    @Unique
    private void fangsu$rebuildIfNeeded() {
        final RailPoseExtra pose = ((RailPoseExtraHolder) (Object) this).getFangSuPose();
        if (pose.affectsGeometry() && !(railMath instanceof FangSuRailMath)) {
            // 走到这里说明主钩子（NEW-redirect）没有解析成功，兜底接管。
            // 正常情况下这条日志不应出现；出现即代表钩子失效但仍能正确工作。
            com.fangsu.Main.LOGGER.warn("[RailPose] NEW-redirect 未生效，使用构造器 RETURN 兜底安装几何");
            rebuildRailMath();
        }
    }

    /**
     * 按当前附加姿态重建几何。{@code RailPoseExtraHolder} 的实现在这里（{@code railMath} 属于
     * {@code Rail}，不能放在 {@code RailSchemaMixin}）。
     * <p>
     * 端点顺序严格复刻 MTR：{@code position1.compareTo(position2) <= 0} 时 MTR 用
     * {@code (position1, position2)} 构造 {@code RailMath}，否则用 {@code (position2, position1)}
     * （见 {@code Rail} 两个构造器的字节码）。顺序错了会让 {@code railMath.getPosition(t, false)}
     * 与列车/寻路的参数方向对不上。
     * <p>
     * 姿态为默认时把 {@code railMath} 还原成 MTR <b>原生</b> {@code RailMath}（不是子类），
     * 保证原版轨道几何逐位一致 —— 这是本特性最重要的不变式。
     */
    public void rebuildRailMath() {
        final RailPoseExtraHolder holder = (RailPoseExtraHolder) (Object) this;
        final RailPoseExtra pose = holder.getFangSuPose();
        // 端点信息来自 RailSchemaMixin 的影子字段（见本类顶部的说明）
        final Position position1 = holder.fangsu$getPosition1();
        final Position position2 = holder.fangsu$getPosition2();
        final Angle angle1 = holder.fangsu$getAngle1();
        final Angle angle2 = holder.fangsu$getAngle2();
        final Rail.Shape shape = holder.fangsu$getShape();
        final double verticalRadius = holder.fangsu$getVerticalRadius();
        final boolean firstIsPosition1 = position1.compareTo(position2) <= 0;
        final Position firstPosition = firstIsPosition1 ? position1 : position2;
        final Angle firstAngle = firstIsPosition1 ? angle1 : angle2;
        final Position secondPosition = firstIsPosition1 ? position2 : position1;
        final Angle secondAngle = firstIsPosition1 ? angle2 : angle1;

        if (!pose.affectsGeometry()) {
            if (railMath == null || railMath.getClass() != RailMath.class) {
                railMath = new RailMath(firstPosition, firstAngle, secondPosition, secondAngle, shape, verticalRadius);
            }
            return;
        }

        railMath = new FangSuRailMath(
                firstPosition, firstAngle, secondPosition, secondAngle,
                shape, verticalRadius, pose, firstIsPosition1
        );
    }

    // ==================== 2. copy 保姿态 ====================

    /** {@code copy(Rail, Shape, double)}（形状/半径编辑路径）。 */
    @Inject(
            method = "copy(Lorg/mtr/core/data/Rail;Lorg/mtr/core/data/Rail$Shape;D)Lorg/mtr/core/data/Rail;",
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private static void fangsu$copyPoseWithShape(Rail source, Rail.Shape newShape, double newVerticalRadius, CallbackInfoReturnable<Rail> cir) {
        fangsu$copyPose(source, cir.getReturnValue());
    }

    /** {@code copy(Rail, ObjectArrayList)}（样式编辑路径）。 */
    @Inject(
            method = "copy(Lorg/mtr/core/data/Rail;Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;)Lorg/mtr/core/data/Rail;",
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private static void fangsu$copyPoseWithStyles(Rail source, ObjectArrayList<String> styles, CallbackInfoReturnable<Rail> cir) {
        fangsu$copyPose(source, cir.getReturnValue());
    }

    /**
     * 把源轨道的附加姿态复制到 copy 出来的新轨道上并重建几何。
     * <p>
     * 两个 {@code copy} 都按 {@code position1 → position2} 的原顺序传参
     * （字节码里是 {@code position1, angle1, position2, angle2}），所以两端的偏移可以 1:1 复制。
     */
    @Unique
    private static void fangsu$copyPose(Rail source, Rail target) {
        if (source == null || target == null) {
            return;
        }
        final RailPoseExtra pose = ((RailPoseExtraHolder) (Object) source).getFangSuPose();
        final RailPoseExtraHolder holder = (RailPoseExtraHolder) (Object) target;
        holder.setFangSuPose(pose);
        holder.rebuildRailMath();
    }
}
