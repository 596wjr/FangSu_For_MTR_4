package com.fangsu.mixin;

import com.fangsu.render.RailRollRenderHelper;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#if MC_VERSION >= 11903
import org.joml.Quaternionf;
//#endif

/**
 * 乘车经过外轨超高（翻滚角）的曲线时，让<b>整个世界画面（含地平线）</b>绕<b>车厢的世界纵轴</b>滚转（P6）。
 * <p>
 * <b>问题</b>：P4b/P4c 之后，轨面、车体、风挡与玩家站位都已经随车体滚转，但玩家的<b>视线仍然水平</b>，
 * 于是「明显倾斜的车厢里挂着一条水平的地平线」—— 这是该特性最后、也是最显眼的一处不协调。
 * <p>
 * <b>做法</b>：在 {@code GameRenderer.renderLevel}（yarn：{@code renderWorld}）里、
 * {@code LevelRenderer.prepareCullFrustum}（yarn：{@code WorldRenderer.setupFrustum}）调用<b>之前</b>，
 * 给世界 PoseStack 右乘一个「绕世界轴 {@code f} 旋转 {@code −φ}」的旋转，其中
 * <pre>
 * φ = 玩家所在车厢的滚转角 × cameraTiltStrength()   （见 RailRollRenderHelper.getCameraRollDegrees）
 * f = 该车厢的世界纵轴 Ry(yaw)·Rx(pitch)·ẑ          （见 RailRollRenderHelper.getCameraTiltAxis）
 * </pre>
 * <p>
 * <b>为什么是车厢纵轴而不是视线轴（P6-AXIS 修复，本文件的核心结论）</b>：
 * {@code poseStack.mulPose(q)}（{@code q = Quaternionf().rotationAxis(θ, a)}）计算的是
 * {@code M ← M·R_a(θ)}，而 {@code M·R_a(θ) = R_{M·a}(θ)·M}。所以 <b>{@code a} 是「{@code M} 所消费的那个
 * 空间」里的轴</b>，也就是<b>世界系</b>的轴。
 * <ul>
 *   <li>旧实现传 {@code a = M⁻¹·ẑ_view}（相机后向），于是 {@code M·R_a(φ) = R_ẑ_view(φ)·M}
 *       —— 这是<b>相机空间 Z 轴滚转（screen-space 滚转）</b>：地平线的屏幕滚转与「玩家朝哪看」无关。
 *       探头数值：车内横轴屏幕角恒为 {@code 0.0000}、地平线屏幕角恒为 {@code −φ}
 *       （yaw ∈ {0,37,90,155,180,270} × look ∈ {0,±45,±90,135,180} 全部相同）。</li>
 *   <li>本次改为传 {@code a = f}（车厢世界纵轴）并取 {@code θ = −φ}：这就是「内容随车厢一起转」的视图矩阵，
 *       也就是「乘客的脑袋焊在车厢上」的物理模型。它给出的形状是
 *       <pre>
 *       车内横轴屏幕角 = 0（恒定，与 look 无关 —— 这就是「车内看起来水平」）
 *       地平线屏幕角   = −φ · cos(look)      look = 视线相对车头的水平夹角
 *       look = 0（朝车头）→ −φ ；look = 180（朝车尾）→ +φ ；look = ±90（正侧向）→ 0
 *       </pre>
 *       朝车头时与旧形式<b>严格等价</b>：那一刻相机后向 {@code a_旧 = M 的第三行 = −f}，
 *       而 {@code R_{−f}(φ) = R_f(−φ)}（同一根轴、同一个角），所以两者是同一个旋转；
 *       数值核对 worst |OLD − NEW| = 5.96e-08（仅浮点往返误差，见 probe 第 (1b) 节）。
 *       朝车尾符号翻转、正侧向退化为纯俯仰 —— 这正是用户描述的「一侧正确、另一侧完全相反」。</li>
 * </ul>
 * <p>
 * <b>为什么它能「车内变水平、地平线倾斜」</b>：整个画面是一起被旋转的（MTR 的列车、轨道、站台
 * 都在 {@code GraphicsHolder} 包着的同一个世界 PoseStack 上绘制），而车厢内部在此之前已经在屏幕上
 * 歪了 {@code φ}（车体自己的 {@code rotateZDegrees(φ)}，绕局部 +Z = f）；把世界绕同一根 f 反向滚 φ
 * 后：车内回到水平、地平线倾斜 φ 度 —— 与「乘客的脑袋随车体一起滚」逐点一致。
 * <p>
 * <b>严格 no-op</b>：{@link RailRollRenderHelper#getCameraRollDegrees()} 在
 * 「未乘车 / 该车厢本帧没渲染 / 滚转为 0 / 配置关闭 / 强度为 0」时返回 {@code 0.0}，
 * 此时本方法<b>在任何矩阵运算之前就 return</b>，世界 PoseStack 与原生逐位一致
 * （不读矩阵、不建四元数、没有 mulPose，也没有读取矩阵之外的任何副作用）。
 * {@link RailRollRenderHelper#getCameraTiltAxis()} 只在前者返回非零之后才被调用。
 * <p>
 * <b>客户端专属</b>：{@code GameRenderer} / {@code PoseStack} 都是客户端类，本 mixin 只注册在
 * {@code fangsu.mixins.json} 的 {@code client} 数组里，服务端不会加载；而
 * {@link RailRollRenderHelper} 里新增的 {@code getCameraRollDegrees()} / {@code getCameraTiltAxis()}
 * 只返回 {@code double} / {@code double[]}、不引用任何客户端类型，因此专用服务端不会因为本特性
 * 而加载到 blaze3d。
 * <p>
 * <b>旧映射（{@code MC_VERSION < 11903}）</b>：那时 {@code PoseStack.mulPose} 的形参是
 * {@code com.mojang.math.Quaternion} 而不是 {@code org.joml.Quaternionf}，本阶段（目标版本 1.20.1）
 * 刻意不为它写第二套分支：钩子照常挂上并推进诊断，但不施加任何矩阵修改，
 * 相机表现与原生逐位一致。这样任何版本都不会因为本特性而崩。
 */
@Mixin(value = GameRenderer.class)
public class GameRendererTiltMixin {

    /**
     * 在 {@code prepareCullFrustum} 之前施加镜头滚转。
     * <p>
     * 形参与被注入方法 {@code renderLevel(float, long, PoseStack)} 逐字对应；
     * 选择的调用点（见 {@link RailRollRenderHelper#PREPARE_CULL_FRUSTUM_TARGET}）在 1.20.1 的
     * {@code GameRenderer.renderLevel} 里唯一，此时栈里已经只有相机旋转
     * {@code R_cam = Rx(camera.getXRot()) · Ry(camera.getYRot() + 180)}（世界栈本身是干净的，
     * 投影矩阵进的是另一个局部 PoseStack），而任何世界几何都还没被变换。
     * <p>
     * {@code method} 与 {@code at.target} 都显式写出（Mixin 0.8.7 对缺 {@code method}/{@code target}
     * 的注入注解直接抛 {@code InvalidInjectionException}）；{@code method} 只写方法名
     * {@code renderLevel}（{@code GameRenderer} 内唯一，理由与完整描述符见
     * {@link RailRollRenderHelper#GAME_RENDERER_RENDER_LEVEL_DESCRIPTOR}）；
     * {@code require = 0} 让未来 MC/MTR 改动这个调用点时退化为「相机无滚转」（= 修复前的现状）
     * 而不是崩游戏，并由 {@link RailRollRenderHelper} 的一次性告警暴露。
     */
    @Inject(
            method = RailRollRenderHelper.GAME_RENDERER_RENDER_LEVEL_TARGET,
            at = @At(
                    value = "INVOKE",
                    target = RailRollRenderHelper.PREPARE_CULL_FRUSTUM_TARGET,
                    shift = At.Shift.BEFORE
            ),
            require = 0
    )
    private void fangsu$tiltCameraWhenRiding(float tickDelta, long limitTime, PoseStack poseStack, CallbackInfo ci) {
        // 钩子存活诊断：只要进入本方法就说明注入解析成功（不论这一帧是否需要滚转）
        RailRollRenderHelper.probeCameraTiltFrame();
        final double angleDegrees = RailRollRenderHelper.getCameraRollDegrees();
        if (angleDegrees == 0.0D || poseStack == null) {
            // 未乘车 / 无滚转 / 配置关闭：一个矩阵乘法都不做，与原生逐位一致
            return;
        }
        //#if MC_VERSION >= 11903
        fangsu$applyCarAxisRoll(poseStack, angleDegrees);
        //#else
        //$$ // 1.18.2 / 1.19.2 的 PoseStack.mulPose 形参是 com.mojang.math.Quaternion，
        //$$ // 本阶段（MTR 4 的目标版本 1.20.1）不提供该分支：钩子照常挂上并计入诊断，
        //$$ // 但不做任何矩阵修改（相机与原生逐位一致）。
        //#endif
    }

    //#if MC_VERSION >= 11903
    /**
     * 把「绕车厢世界纵轴 {@code f} 旋转 {@code −angleDegrees}」表达成对世界 PoseStack 的一次右乘。
     * <p>
     * <b>轴</b>：{@link RailRollRenderHelper#getCameraTiltAxis()}，即车厢的
     * {@code f = Ry(yaw)·Rx(pitch)·ẑ}（含 pitch；推导与字节码证据见该方法的 javadoc）。
     * 轴为 {@code null}（没有有效车厢 / 异常数据）时<b>不旋转</b>：宁可退回原生画面，
     * 也绝不用 {@code null} 或零长度轴去建四元数（那会污染整个世界矩阵）。
     * <p>
     * <b>符号</b>：{@code −angleDegrees}。本方法的入参 φ 是「车厢沿前进方向的右手侧抬升」，
     * 物理模型是「画面内容随车厢一起转」，即世界绕车厢纵轴反向滚 φ，也就是 {@code M·R_f(−φ)}。
     * 这个符号是被三条独立证据钉住的：
     * <ol>
     *   <li>朝车头看时 {@code a_旧 = −f}，{@code R_{−f}(φ) = R_f(−φ)}，因此本式与用户已验收的
     *       旧画面严格相同（worst 5.96e-08，probe 第 (1b) 节）；取正号会立刻变成 +2φ（probe 第 (7) 节）；</li>
     *   <li>车内横轴屏幕角在所有 look 上都是 0.0000（probe 第 (3) 节），即车厢内部看起来水平；</li>
     *   <li>地平线屏幕角 = −φ·cos(look)，朝车尾翻成 +φ（probe 第 (2)/(4) 节），与用户的「一侧正确、
     *       另一侧完全相反」一致。</li>
     * </ol>
     * 取正号（{@code R_f(+φ)}）会让车内横轴变成 −2φ（越滚越歪）。
     * <p>
     * 只读取 helper 的缓存并做这一条 {@code mulPose}；除世界栈顶矩阵外不修改任何东西，
     * 也不做任何矩阵读取（轴来自 {@link RailRollRenderHelper#getCameraTiltAxis()} 的闭式表达式，
     * 不依赖任何模组往栈里写的额外旋转 —— 车厢的姿态本来就只由 MTR 的 PnR 决定）。
     */
    private static void fangsu$applyCarAxisRoll(PoseStack poseStack, double angleDegrees) {
        final double[] axis = RailRollRenderHelper.getCameraTiltAxis();
        if (axis == null || axis.length != 3) {
            // 没有可用的车厢纵轴 → 退回原生（不做任何旋转）
            return;
        }
        final double axisX = axis[0];
        final double axisY = axis[1];
        final double axisZ = axis[2];
        if (!Double.isFinite(axisX) || !Double.isFinite(axisY) || !Double.isFinite(axisZ)) {
            return;
        }
        poseStack.mulPose(new Quaternionf().rotationAxis(
                (float) Math.toRadians(-angleDegrees),
                (float) axisX,
                (float) axisY,
                (float) axisZ
        ));
    }
    //#endif
}
