package com.fangsu.mixin;

import com.fangsu.Main;
import com.fangsu.render.RailRollRenderHelper;
import com.fangsu.train.VehicleLcdRenderer;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.PositionAndRotation;
import org.mtr.mod.render.RenderVehicles;
import org.mtr.mod.render.StoredMatrixTransformations;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * 车载 LCD 的<b>备用</b>渲染注入点（不依赖任何局部变量捕获）。
 * <p>
 * 主路径是 JCM 的车辆脚本链路（见 {@code JcmLcdScriptBridge}）——车型一旦被接入脚本，
 * 由 JCM 的 {@code RenderVehiclesMixin} 驱动 create / render / dispose，
 * 本注入点会因为 {@code VehicleLcdRenderer.renderCar} 内部的
 * {@code isScripted} 判断而跳过，不会重复渲染。
 * <p>
 * 注入位置与旧实现一致：{@code RenderVehicles.render} 里
 * {@code vehicles.forEach(...)} 之前。此处没有任何可用的车厢局部变量，
 * 所以自己去把每节车厢的变换算出来：
 * <ul>
 *   <li>车辆列表来自 {@code MinecraftClientData.getInstance().vehicles}；</li>
 *   <li>车厢位置/朝向来自 JCM 的 {@code NTETrainWrapper}（内部就是
 *       {@code vehicleExtension.getSmoothedVehicleCarsAndPositions(0)}）；</li>
 *   <li>矩阵直接调 MTR 自己的
 *       {@link RenderVehicles#getStoredMatrixTransformations(boolean, org.mtr.mod.render.PositionAndRotation, double)}。</li>
 * </ul>
 * 刻意<b>不</b>用 {@code LocalCapture}：之前那版对 {@code lambda$render$5}
 * 做局部变量捕获，Mixin 报 {@code Scanned 0 target(s)} 直接崩溃。
 * <p>
 * <b>P4b-2 车体滚转</b>的注入点也在这里：
 * <ul>
 *   <li>帧头刷新「带滚转的轨道」缓存（{@link RailRollRenderHelper#beginVehicleFrame()}）；</li>
 *   <li>{@code getStoredMatrixTransformations} 的 {@code @At("RETURN")} 上追加绕车体前进轴的滚转。
 *       该方法同时被转向架、乘车玩家与电梯调用，因此车体滚转的定位使用
 *       「车体中心到带滚转轨道中心线的水平距离 &lt; 1.5 m（容纳紧曲线上的转向架弦中点矢高）、
 *       高差 &lt; 1.0 m、且车头与轨道切向 |cos| &ge; 0.9」的匹配，
 *       匹配不到就不追加任何变换（普通轨道、横穿轨道、远处几何一律走原生路径）。
 *       该方法的第一个参数是 {@code useOffset}（= {@code offsetVector == null}），
 *       <b>乘车时传 {@code false} 且 {@code PositionAndRotation} 是相机相对量</b>，
 *       所以另有一个 {@code @Redirect} 在 {@code lambda$render$14} 里记下本车的世界 PnR；</li>
 *   <li>{@code lambda$render$12} 里的两个 {@code renderConnection} 调用用 {@code @ModifyArgs}
 *       把滚转角加到 {@code oscillationAmount} 上，风挡与挡板因此与车体同步倾斜。</li>
 * </ul>
 */
@Mixin(value = RenderVehicles.class, remap = false)
public class RenderVehiclesMixin {

    @Inject(
            method = "render(JLorg/mtr/mapping/holder/Vector3d;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArraySet;forEach(Ljava/util/function/Consumer;)V"
            ),
            remap = false
    )
    private static void fangsu$renderVehicleLcd(long millisElapsed,
                                                org.mtr.mapping.holder.Vector3d cameraShakeOffset,
                                                CallbackInfo ci) {
        try {
            for (VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
                VehicleLcdRenderer.renderVehicle(vehicle);
            }
        } catch (Throwable t) {
            Main.LOGGER.error("[FangSu LCD] 备用渲染路径失败", t);
        }
    }

    /**
     * 每帧刷新车体滚转用的「带滚转轨道」缓存。
     * <p>
     * 放在 {@code render} 头部而不是按需惰性重建，是为了让缓存与渲染帧一一对应，
     * 轨道被编辑（新增/删除/改姿态）后最多一帧就同步。
     * <p>
     * 这里同时调用 {@link RailRollRenderHelper} 的一次性钩子诊断（D8）：本钩子若也失效，
     * RenderRails 侧的同类探针仍能报告。而「本钩子与 P4b-1 侧钩子同时漏挂」这一最坏形态由
     * {@code RenderRails.render} 的 {@code @At("TAIL")} 看门狗兜底 —— 那是本特性里唯一
     * {@code require = 1} 的注入点，不可静默失效，它每帧推进
     * {@link RailRollRenderHelper#probeRenderFrame()} 的时钟并独立扫客户端轨道缓存，
     * 因此这种形态会得到一条明确的 “[RailRoll] 全部滚转钩子都未触发” 告警（D-C）。
     */
    @Inject(method = "render(JLorg/mtr/mapping/holder/Vector3d;)V", at = @At("HEAD"), require = 0, remap = false)
    private static void fangsu$beginVehicleFrame(long millisElapsed,
                                                 org.mtr.mapping.holder.Vector3d cameraShakeOffset,
                                                 CallbackInfo ci) {
        RailRollRenderHelper.beginVehicleFrame();
    }

    /**
     * 车体随轨滚转（P4b-2）。
     * <p>
     * MTR 自己的链是 {@code translate → rotateYRadians(yaw+π) → rotateXRadians(pitch+π)
     * → rotateZDegrees(Oscillation)}：第三个参数只是外观摇晃量，转向架 / 乘车玩家 / 电梯都传 0。
     * 这里在 {@code @At("RETURN")} 追加一个消费者，等价于在该链之后继续
     * 「绕局部 {@code +Z}（= 车头前进方向）滚转」；<b>轴心就是车体原点，不加任何平移</b>，
     * 理由与推导见 {@link RailRollRenderHelper#applyTrainRoll}。
     * 无滚转时不追加，返回值与原生逐位相同。
     * <p>
     * <b>第一个参数是 {@code useOffset} 而不是 {@code isReversed}（BUG 1 的命名错误，本次已修）</b>：
     * MTR 源码里它就是 {@code offsetVector == null}，javadoc 原文为
     * “{@code true} if the vehicle is not being ridden in”。为 {@code true}（未乘车）时
     * {@code positionAndRotation} 是该车的<b>世界</b> PnR，直接用；为 {@code false}（乘车）时它是
     * {@code getRenderPositionAndRotation} 换算出的<b>相机相对</b>量，几何匹配必然失败，
     * 因此改由 {@code lambda$render$14} 的 {@code @Redirect}（{@link #fangsu$captureCarFrame}）
     * 记下的本车世界 PnR 来计算 —— 详见
     * {@link RailRollRenderHelper#applyTrainRoll(StoredMatrixTransformations, PositionAndRotation, boolean)}。
     */
    @Inject(
            method = "getStoredMatrixTransformations(ZLorg/mtr/mod/render/PositionAndRotation;D)Lorg/mtr/mod/render/StoredMatrixTransformations;",
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private static void fangsu$rollVehicleBody(boolean useOffset,
                                               PositionAndRotation positionAndRotation,
                                               double rotationDegrees,
                                               CallbackInfoReturnable<StoredMatrixTransformations> cir) {
        RailRollRenderHelper.applyTrainRoll(cir.getReturnValue(), positionAndRotation, useOffset);
    }

    /**
     * 记录「当前正在渲染的车厢」的世界 PnR（BUG 1 的乘车分支 + BUG 2 的共同数据源）。
     * <p>
     * {@code lambda$render$14} 是 4.0.5 里<b>每节车厢</b>的渲染函数，它在该车 PnR 被换算成相机相对量
     * 之前调用 {@code RenderVehicles.getRenderPositionAndRotation(offsetVector, offsetRotation,
     * ridingCarPnR, absoluteVehicleCarPnR, cameraShakeOffset)}；第 4 个实参（局部变量槽位 21）
     * 就是该车自己的 {@code absoluteVehicleCarPositionAndRotation}（世界 PnR，含世界位置与世界偏航）。
     * 这里重定向这一次调用，在转交原方法之前把它交给
     * {@link RailRollRenderHelper#beginCarFrame(PositionAndRotation)}，
     * 于是同一车随后的车体 {@code StoredMatrixTransformations}（偏移 562）与风挡顶点（偏移 642 起）
     * 都能沿用「未乘车」那一套几何匹配，车体与风挡不会给出不同的滚转角。
     * <p>
     * {@code method} 必须显式写出（Mixin 0.8.7 对缺 {@code method}/{@code target} 的
     * {@code @Redirect} 直接抛 {@code InvalidInjectionException}）；synthetic lambda 只能写方法名
     * （见 {@link RailRollRenderHelper#CAR_FRAME_TARGET}）。该重载在 {@code lambda$render$14}
     * 内只出现一次，所以这个重定向不改动别的任何调用点；{@code require = 0} 让未来 MTR 改编号时
     * 退化为「乘车分支不倾斜」而不是崩游戏，并由 {@link RailRollRenderHelper} 的一次性告警暴露。
     */
    @Redirect(
            method = RailRollRenderHelper.CAR_FRAME_TARGET,
            at = @At(
                    value = "INVOKE",
                    target = RailRollRenderHelper.GET_RENDER_POSITION_AND_ROTATION_TARGET
            ),
            require = 0,
            remap = false
    )
    private static PositionAndRotation fangsu$captureCarFrame(
            Vector3d offsetVector,
            Double offsetRotation,
            PositionAndRotation ridingCarPositionAndRotation,
            PositionAndRotation absoluteVehicleCarPositionAndRotation,
            Vector3d cameraShakeOffset
    ) {
        RailRollRenderHelper.beginCarFrame(absoluteVehicleCarPositionAndRotation);
        return RenderVehicles.getRenderPositionAndRotation(
                offsetVector, offsetRotation, ridingCarPositionAndRotation,
                absoluteVehicleCarPositionAndRotation, cameraShakeOffset
        );
    }

    /**
     * 风挡 / 挡板随车体滚转（BUG 2 的修复）。
     * <p>
     * {@code renderConnection} 把摆动量 {@code oscillationAmount}（度）当作
     * {@code new Vector(...).rotateZ(-Math.toRadians(oscillationAmount))} 的局部滚转
     * （真实 4.0.5 字节码：偏移 22–28 求 {@code -toRadians(形参 17)}，偏移 68–70 调
     * {@code Vector.rotateZ}），与车体 {@code rotateZDegrees(osc + roll)} <b>同号同轴</b>：
     * {@code Vector.rotateZ(θ)} 只是右手 {@code Rz} 的转置（= {@code Rz(−θ)}），代入
     * {@code θ = −toRadians(osc)} 后得到右手 {@code Rz(+osc°)}；风挡局部系与车体模型局部系相差
     * {@code F = diag(−1,−1,1)}（绕 Z 转 π，可与绕 Z 的旋转交换），所以「两边同一个物理点重合」
     * 的充要条件就是 {@code osc' = osc + roll}。<b>因此是加号，不是减号</b>
     * （参考实现 MAGIC 用的是减号，其轨道截面滚转约定与本工程相反；数值证明见
     * {@code build/tmp/rollfix3/ConnectionRollTest}：加号时顶点差 0.000e+00，减号时 5° 差 0.635 m）。
     * <p>
     * 滚转角由 {@link RailRollRenderHelper#getConnectionRollDegrees} 给出，取值与同一车的车体
     * （{@link #fangsu$rollVehicleBody}）完全一致：未乘车时用 {@code renderConnection} 自己收到的
     * 世界 PnR，乘车时用 {@link #fangsu$captureCarFrame} 记下的本车世界 PnR。
     * <p>
     * <b>作用面</b>：全 jar 只有两个 {@code renderConnection} 调用点，都在 {@code lambda$render$12}
     * （偏移 200 风挡 / 偏移 333 挡板，已用 javap 与 ASM 全 jar 扫描双向核实），
     * 它们就是本 {@code @ModifyArgs} 唯一会改的两处；其它方法、其它类一概不受影响。
     * {@code method} 显式写出、{@code require = 0}，理由同上。
     * <p>
     * <b>为什么用 {@code @ModifyArgs} 而不是 {@code @Redirect}</b>：{@code renderConnection} 是
     * {@code private static}，{@code @Redirect} 的处理器必须自己完成等价调用，而本包无法访问该私有方法
     * （只能反射，或把整段实现抄一遍 —— 两者都会引入新的、更脆的失败点）。{@code @ModifyArgs}
     * 保留原调用、只改一个实参，是最窄且不改语义的做法。它不使用 {@code ordinal}，
     * 定位完全靠 {@code method = lambda$render$12} + 精确的 {@code INVOKE} 描述符；
     * 万一未来 MTR 在该 lambda 里再加一个同签名的 {@code renderConnection} 调用，
     * 唯一后果是那一处也会被加上同一个本车滚转角（语义上仍正确），而不会改到别的类；
     * 若 MTR 改名或改签名，则钩子静默失效并由 {@link RailRollRenderHelper} 的一次性告警暴露。
     */
    @ModifyArgs(
            method = RailRollRenderHelper.CONNECTION_CALLER_TARGET,
            at = @At(
                    value = "INVOKE",
                    target = RailRollRenderHelper.RENDER_CONNECTION_DESCRIPTOR
            ),
            require = 0,
            remap = false
    )
    private static void fangsu$rollConnection(Args args) {
        final Object connectionPositionAndRotation = args.get(RailRollRenderHelper.CONNECTION_POSITION_AND_ROTATION_ARG);
        final Object useOffset = args.get(RailRollRenderHelper.CONNECTION_USE_OFFSET_ARG);
        final double rollDegrees = RailRollRenderHelper.getConnectionRollDegrees(
                connectionPositionAndRotation instanceof PositionAndRotation
                        ? (PositionAndRotation) connectionPositionAndRotation
                        : null,
                Boolean.TRUE.equals(useOffset)
        );
        if (rollDegrees == 0.0D) {
            // 无滚转 → 一个实参都不动，调用与 MTR 原生逐位一致
            return;
        }
        final Object oscillationAmount = args.get(RailRollRenderHelper.CONNECTION_OSCILLATION_ARG);
        if (oscillationAmount instanceof Double) {
            args.set(
                    RailRollRenderHelper.CONNECTION_OSCILLATION_ARG,
                    (Double) oscillationAmount + rollDegrees
            );
        }
    }
}
