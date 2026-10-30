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
 * MTR4 补充线路详情通道，保留 hidden_routes_* 历史包名。
 * 明确指定 ID 时可补取普通线路和隐藏线路；空请求仍只返回全部隐藏线路。
 * 客户端同时写入方速补充缓存与 JCM 的公开数据缓存，不参与 JCM 绘制链路。
 */
public class HiddenRoutesPackets {

	private static boolean clientRegistered;

	/** C2S：客户端按 routeId 请求线路的 SimplifiedRoute（包名沿用旧版） */
	public static final ResourceLocation HIDDEN_ROUTES_REQUEST = new ResourceLocation("fangsu", "hidden_routes_request");
	/** S2C：服务端回传 SimplifiedRoute 形状的 JSON（{"routes":[...]}） */
	public static final ResourceLocation HIDDEN_ROUTES_RESPONSE = new ResourceLocation("fangsu", "hidden_routes_response");

	/** 服务端：C2S 接收器注册（由 {@link ModNetwork#init} 调用） */
	public static void registerServer() {
		NetworkManager.registerReceiver(NetworkManager.Side.C2S, HIDDEN_ROUTES_REQUEST, HiddenRoutesPackets::handleHiddenRoutesRequest);
	}

	/** 客户端：S2C 接收器注册 + mtrscripting（JCM）探测（由 {@link com.fangsu.MainClient#initClient} 调用） */
	public static void registerClient() {
		if (clientRegistered) return;
		clientRegistered = true;
		NetworkManager.registerReceiver(NetworkManager.Side.S2C, HIDDEN_ROUTES_RESPONSE, HiddenRoutesPackets::handleHiddenRoutesResponse);
		HiddenRoutesClient.detect();
		HiddenRoutesClient.initLifecycle();
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
						// 空请求仍只取隐藏线路；明确指定 ID 时返回对应线路，不再过滤普通线路。
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

	/** JCM 站序适配和 LCD 共用：请求指定 routeId 的线路详情 */
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
