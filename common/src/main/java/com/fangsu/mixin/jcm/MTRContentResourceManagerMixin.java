package com.fangsu.mixin.jcm;

import com.fangsu.train.JcmLcdScriptBridge;
import com.fangsu.mtr.LcdVehicleRegistry;
import com.lx862.mtrscripting.core.primitive.ParsedScript;
import com.lx862.mtrscripting.mod.resource.MTRContentResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * 把「原生 MTR 配置里带 {@code lcd} 的车型」接进 JCM 的车辆脚本链路。
 * <p>
 * 注入点在 JCM {@code MTRContentResourceManager.reload()} 的 {@code RETURN}：
 * <ul>
 *   <li>此时 JCM 已解析完 {@code mtr:mtr_custom_resources.json} 的
 *       {@code vehicles[].scriptId} 与 {@code vehicleScripts[]}；</li>
 *   <li>它已经 clear 过 {@code vehicleScripts} / {@code vehicleScriptIds}，我们补进去的条目
 *       不会被清掉；</li>
 *   <li>它的「脚本缺失」校验循环也已跑完，不会再误报警告。</li>
 * </ul>
 * 之后 JCM 自己的 {@code RenderVehiclesMixin} 就会按车型 ID 找到方速的
 * {@code fangsu_lcd} 脚本并驱动 create / render / dispose，
 * 车厢变换、朝向、光照全部由 JCM 负责。
 * <p>
 * 两张表是 {@code MTRContentResourceManager} 的私有静态字段，用 {@code @Shadow} 访问。
 */
@Mixin(value = MTRContentResourceManager.class, remap = false)
public class MTRContentResourceManagerMixin {

    @Shadow(remap = false)
    private static Map<String, ParsedScript> vehicleScripts;

    @Shadow(remap = false)
    private static Map<String, String> vehicleScriptIds;

    @Inject(method = "reload", at = @At("RETURN"), remap = false)
    private static void fangsu$injectLcdScript(CallbackInfo ci) {
        // 先让桥接层把原生配置扫一遍，注册表据此重建
        JcmLcdScriptBridge.onJcmReload(vehicleScripts, vehicleScriptIds);
        LcdVehicleRegistry.load();
    }
}
