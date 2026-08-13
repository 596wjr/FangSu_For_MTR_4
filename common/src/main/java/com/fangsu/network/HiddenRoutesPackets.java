package com.fangsu.network;

import com.fangsu.mixin.InitAccessorMixin;
import com.fangsu.mixin.MainAccessorMixin;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.mtr.core.simulation.Simulator;
import org.mtr.mod.Init;

import java.util.ArrayList;
import java.util.List;

/**
 * 隐藏线路 SimplifiedRoute 专用通道（仅 MTR4 版）。
 * <p>
 * 原版 TSC 服务端 {@code SimplifiedRoute.addToList} 无条件过滤隐藏线路，导致 JCM 脚本中
 * {@code stop.route} 在「全部线路隐藏」的世界里恒为 null。本通道由 JCM 侧的 mixin
 * （{@code com.fangsu.mixin.jcm.StopsDataS2CPacketMixin}）在 JCM 自己的数据请求旁触发，
 * 服务端只回传「被请求且隐藏」的线路，客户端经 JCM 的公开静态入口
 * {@code VehicleDataCache.putMTRDataCache} 合并进 JCM 脚本数据，不触碰 MTR 主通道。
 */
public class HiddenRoutesPackets {

	/** C2S：客户端按 routeId 请求隐藏线路的 SimplifiedRoute */
	public static final ResourceLocation HIDDEN_ROUTES_REQUEST = new ResourceLocation("fangsu", "hidden_routes_request");
	/** S2C：服务端回传 SimplifiedRoute 形状的 JSON（{"routes":[...]}） */
	public static final ResourceLocation HIDDEN_ROUTES_RESPONSE = new ResourceLocation("fangsu", "hidden_routes_response");

	/** 服务端：C2S 接收器注册（由 {@link ModNetwork#init} 调用） */
	public static void registerServer() {
		NetworkManager.registerReceiver(NetworkManager.Side.C2S, HIDDEN_ROUTES_REQUEST, HiddenRoutesPackets::handleHiddenRoutesRequest);
	}

	/** 客户端：S2C 接收器注册 + mtrscripting（JCM）探测（由 {@link com.fangsu.MainClient#initClient} 调用） */
	public static void registerClient() {
		NetworkManager.registerReceiver(NetworkManager.Side.S2C, HIDDEN_ROUTES_RESPONSE, HiddenRoutesPackets::handleHiddenRoutesResponse);
		HiddenRoutesClient.detect();
	}

	private static void handleHiddenRoutesRequest(FriendlyByteBuf buf, NetworkManager.PacketContext ctx) {
		final int count = buf.readInt();
		final List<Long> routeIds = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			routeIds.add(buf.readLong());
		}

		ctx.queue(() -> {
			final ServerPlayer player = (ServerPlayer) ctx.getPlayer();
			if (player == null) return;
			//#if MC_VERSION >= 12000
			final Level level = player.level();
			//#else
			//$$ final Level level = player.level;
			//#endif
			// 维度匹配（与 MtrTicketSystem 同款）：JCM 模式——遍历 TSC 全部 Simulator 按 dimension 定位
			final String dimensionId = Init.getWorldId(new org.mtr.mapping.holder.World(level));
			final org.mtr.core.Main tscInstance = InitAccessorMixin.getMain();
			for (Simulator simulator : ((MainAccessorMixin) tscInstance).getSimulators()) {
				if (simulator.dimension.equals(dimensionId)) {
					// simulator.run 在模拟器线程内安全读取数据；回包经主线程 submit，与 JCM 自身流程一致
					simulator.run(() -> {
						// 空请求 = 全量隐藏线路（世界进入时方速侧拉取），否则按 id 过滤（JCM 站序触发）
						final org.mtr.libraries.com.google.gson.JsonArray routesArray = routeIds.isEmpty()
								? HiddenRouteData.buildAllHiddenRoutesJson(simulator)
								: HiddenRouteData.buildRoutesJson(simulator, routeIds);
						final org.mtr.libraries.com.google.gson.JsonObject root = new org.mtr.libraries.com.google.gson.JsonObject();
						root.add("routes", routesArray);
						player.server.submit(() -> {
							final FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
							response.writeUtf(root.toString());
							NetworkManager.sendToPlayer(player, HIDDEN_ROUTES_RESPONSE, response);
						});
					});
					break;
				}
			}
		});
	}

	private static void handleHiddenRoutesResponse(FriendlyByteBuf buf, NetworkManager.PacketContext ctx) {
		final String json = buf.readUtf();
		ctx.queue(() -> HiddenRoutesClient.mergeHiddenRoutes(json));
	}

	/** JCM mixin 调用：向服务端请求指定 routeId 的隐藏线路数据 */
	public static void sendHiddenRoutesRequestC2S(List<Long> routeIds) {
		if (routeIds == null || routeIds.isEmpty()) return;
		final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeInt(routeIds.size());
		routeIds.forEach(buf::writeLong);
		NetworkManager.sendToServer(HIDDEN_ROUTES_REQUEST, buf);
	}

	/** 请求全部隐藏线路（count=0 表示全量；世界进入时由 {@link HiddenRoutesClient#onWorldJoin} 调用） */
	public static void requestFullHiddenRoutesC2S() {
		final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeInt(0);
		NetworkManager.sendToServer(HIDDEN_ROUTES_REQUEST, buf);
	}
}
