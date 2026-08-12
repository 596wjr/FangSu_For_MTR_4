package com.fangsu.mixin.jcm;

import com.fangsu.network.HiddenRoutesPackets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.ArrayList;
import java.util.List;

/**
 * JCM 站序数据到达时，把「缺失的线路 id」（含隐藏线路——隐藏线路永不进入
 * {@code VehicleDataCache.mtrData}，故永远不会被 JCM 自己的 removeIf 过滤掉）
 * 顺带转发给 FangSu 隐藏线路通道。注入点选在 JCM 发送自己数据请求之前，
 * 两个请求同 tick 发出、响应同序到达，与 JCM 自身数据的时序行为一致。
 * <p>
 * 仅客户端分区；config {@code fangsu-jcm.mixins.json} 为 required:false，
 * 未装 JCM 时整个 mixin 静默跳过。目标类用 targets 字符串形式，
 * 编译期不需要 JCM 类（FangSu 不声明 JCM 编译依赖）。
 */
@Mixin(targets = "com.lx862.jcm.mod.network.scripting.StopsDataS2CPacket", remap = false)
public abstract class StopsDataS2CPacketMixin {

	@Inject(
			method = "runClient",
			at = @At(value = "INVOKE", target = "Lcom/lx862/jcm/mod/registry/Networking;sendPacketToServer(Lorg/mtr/mapping/registry/PacketHandler;)V"),
			locals = LocalCapture.CAPTURE_FAILSOFT
	)
	private void fangsu$requestHiddenRoutes(CallbackInfo ci, List<Long> stationIds, List<Long> routeIds, List<Long> platformIds, List<Long> sidingIds) {
		// LocalCapture 按局部变量声明顺序捕获；捕获失败（JCM 版本变化）时参数为 null，判空防御
		if (routeIds != null && !routeIds.isEmpty()) {
			HiddenRoutesPackets.sendHiddenRoutesRequestC2S(new ArrayList<>(routeIds));
		}
	}
}
