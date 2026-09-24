package com.fangsu.mixin;

import com.fangsu.Main;
import com.fangsu.train.VehicleLcdRenderer;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.RenderVehicles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
}
