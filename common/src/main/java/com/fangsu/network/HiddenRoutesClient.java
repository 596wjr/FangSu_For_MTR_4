package com.fangsu.network;

import com.fangsu.Main;
import com.fangsu.mtr.DrawableRoute;
import dev.architectury.event.events.client.ClientTickEvent;
import org.jetbrains.annotations.Nullable;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * 补充线路客户端缓存与 JCM 公开数据合并桥，保留旧类名和查询方法以兼容现有调用。
 * 同时接收按 ID 补取的普通线路以及世界进入时拉取的隐藏线路。
 * 线路详情优先供方速查询；JCM 可用时也经 putMTRDataCache 合并其站序数据集。
 */
public final class HiddenRoutesClient {

	private static boolean available = false;
	private static Constructor<?> holderCtor;
	private static Method putMTRDataCache;

	/** 补充线路缓存：routeId → SimplifiedRoute（主线程读写，MtrUtil 查询链回退用） */
	private static final Map<Long, SimplifiedRoute> hiddenRoutes = new HashMap<>();
	private static final Map<Long, Long> nextRequests = new HashMap<>();
	private static boolean lifecycleRegistered;
	private static Object lastWorld;

	private HiddenRoutesClient() {
	}

	/** 数据缓存必须随世界切换，通道注册与资源重载分离。 */
	public static void initLifecycle() {
		if (lifecycleRegistered) return;
		lifecycleRegistered = true;
		ClientTickEvent.CLIENT_POST.register(client -> {
			if (client.level == lastWorld) return;
			if (client.level != null && client.player == null) return;
			lastWorld = client.level;
			if (client.level == null) clear();
			else onWorldJoin();
		});
	}

	/** LCD 独立补取缺失线路详情，不依赖 JCM 的绘制或脚本回调；同 ID 每 5 秒最多请求一次。 */
	public static void requestMissingRoutes(long... routeIds) {
		final long now = System.nanoTime();
		final List<Long> requested = new ArrayList<>();
		for (long id : routeIds) {
			// MTR 的有效 ID 可以为负数，只有 0 表示没有线路。
			final Long nextRequest = nextRequests.get(id);
			if (id == 0 || hiddenRoutes.containsKey(id) || requested.contains(id)
					|| (nextRequest != null && now - nextRequest < 0)) continue;
			requested.add(id);
		}
		if (requested.isEmpty()) return;
		HiddenRoutesPackets.sendHiddenRoutesRequestC2S(requested);
		requested.forEach(id -> nextRequests.put(id, now + 5_000_000_000L));
	}

	/** 客户端初始化时探测 mtrscripting 是否加载，缓存反射句柄 */
	public static void detect() {
		try {
			final Class<?> holderClass = Class.forName("com.lx862.mtrscripting.mod.impl.mtr.MTRDatasetHolder", false, HiddenRoutesClient.class.getClassLoader());
			final Class<?> cacheClass = Class.forName("com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleDataCache", false, HiddenRoutesClient.class.getClassLoader());
			holderCtor = holderClass.getConstructor(String.class);
			putMTRDataCache = cacheClass.getMethod("putMTRDataCache", holderClass);
			available = true;
		} catch (Exception e) {
			Main.LOGGER.info("[FangSu] JCM (mtrscripting) 未加载，补充线路仅进入方速缓存");
		}
	}

	/** S2C 响应：JSON（{"routes":[...]}）→ TSC 直接反序列化入缓存 → 反射合并进 JCM 脚本数据 */
	public static void mergeHiddenRoutes(String json) {
		final int[] received = {0};
		try {
			final JsonReader jsonReader = new JsonReader(Utilities.parseJson(json));
			// 多次响应可能分批到达，clearList 用空实现，仅世界切换时由 clear() 整体清空
			jsonReader.iterateReaderArray("routes", () -> {
			}, reader -> {
				final SimplifiedRoute route = new SimplifiedRoute(reader);
				hiddenRoutes.put(route.getId(), route);
				nextRequests.remove(route.getId());
				received[0]++;
			});
		} catch (Exception e) {
			Main.LOGGER.error("[FangSu] 补充线路 JSON 解析失败", e);
			return;
		}
		if (received[0] > 0) {
			DrawableRoute.clearCache();
			Main.LOGGER.info("[FangSu 线路数据] 已接收 {} 条线路详情，补充缓存共 {} 条", received[0], hiddenRoutes.size());
		}
		if (!available) return;
		try {
			final Object holder = holderCtor.newInstance(json);
			putMTRDataCache.invoke(null, holder);
		} catch (Exception e) {
			Main.LOGGER.error("[FangSu] 补充线路数据合并失败", e);
		}
	}

	/** 按 id 查询补充线路（保留旧方法名，MtrUtil 主通道未命中时回退） */
	@Nullable
	public static SimplifiedRoute getHiddenRouteById(long routeId) {
		return hiddenRoutes.get(routeId);
	}

	/** 全部补充线路（保留旧方法名，MtrUtil.getSimplifiedRoutes 等合并用） */
	public static Collection<SimplifiedRoute> getHiddenRoutes() {
		return hiddenRoutes.values();
	}

	/** 离开世界/世界切换时清空缓存 */
	public static void clear() {
		hiddenRoutes.clear();
		nextRequests.clear();
		DrawableRoute.clearCache();
	}

	/** 进入新世界：清空缓存并全量请求隐藏线路（方速 PIDS/RIS 显示、MtrUtil 查询用） */
	public static void onWorldJoin() {
		clear();
		HiddenRoutesPackets.requestFullHiddenRoutesC2S();
	}
}
