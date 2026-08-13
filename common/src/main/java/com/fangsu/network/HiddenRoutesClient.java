package com.fangsu.network;

import com.fangsu.Main;
import org.jetbrains.annotations.Nullable;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 隐藏线路客户端数据层：JCM 脚本数据合并桥 + 方速自身查询缓存（客户端）。
 * <p>
 * 两条用途共用同一条 S2C 通道：
 * <ol>
 *   <li>JCM 脚本数据：编译期不可见 mtrscripting（fabric 运行时无 JCM），运行时反射调用
 *       {@code new MTRDatasetHolder(json)}（TSC 反序列化）+ {@code VehicleDataCache.putMTRDataCache(holder)}
 *       （公开静态，addFrom + 重建 routeIdMap）——只让隐藏线路进入 JCM 脚本数据面；</li>
 *   <li>方速查询缓存：同一份 JSON 用 TSC 原生 API 直接反序列化（与 {@code MTRDatasetHolder} 同款写法）
 *       存入 {@link #hiddenRoutes}，供方速 PIDS/RIS/MtrUtil 的线路查询链使用（隐藏线路不进入 MTR 主通道
 *       {@code MinecraftClientData}，只能走这里）。</li>
 * </ol>
 * 探测失败（未装 JCM）时仅缓存生效——服务端照样回包，方速自己的显示正常。
 */
public final class HiddenRoutesClient {

	private static boolean available = false;
	private static Constructor<?> holderCtor;
	private static Method putMTRDataCache;

	/** 隐藏线路缓存：routeId → SimplifiedRoute（主线程读写，MtrUtil 查询链回退用） */
	private static final Map<Long, SimplifiedRoute> hiddenRoutes = new HashMap<>();

	private HiddenRoutesClient() {
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
			Main.LOGGER.info("[FangSu] JCM (mtrscripting) 未加载，隐藏线路仅进入方速缓存");
		}
	}

	/** S2C 响应：JSON（{"routes":[...]}）→ TSC 直接反序列化入缓存 → 反射合并进 JCM 脚本数据 */
	public static void mergeHiddenRoutes(String json) {
		try {
			final JsonReader jsonReader = new JsonReader(Utilities.parseJson(json));
			// 多次响应可能分批到达，clearList 用空实现，仅世界切换时由 clear() 整体清空
			jsonReader.iterateReaderArray("routes", () -> {
			}, reader -> {
				final SimplifiedRoute route = new SimplifiedRoute(reader);
				hiddenRoutes.put(route.getId(), route);
			});
		} catch (Exception e) {
			Main.LOGGER.error("[FangSu] 隐藏线路 JSON 解析失败", e);
			return;
		}
		if (!available) return;
		try {
			final Object holder = holderCtor.newInstance(json);
			putMTRDataCache.invoke(null, holder);
		} catch (Exception e) {
			Main.LOGGER.error("[FangSu] 隐藏线路数据合并失败", e);
		}
	}

	/** 按 id 查询隐藏线路（MtrUtil 查询链在主通道 miss 后回退） */
	@Nullable
	public static SimplifiedRoute getHiddenRouteById(long routeId) {
		return hiddenRoutes.get(routeId);
	}

	/** 全部隐藏线路（MtrUtil.getSimplifiedRoutes 等合并用） */
	public static Collection<SimplifiedRoute> getHiddenRoutes() {
		return hiddenRoutes.values();
	}

	/** 离开世界/世界切换时清空缓存 */
	public static void clear() {
		hiddenRoutes.clear();
	}

	/** 进入新世界：清空缓存并全量请求隐藏线路（方速 PIDS/RIS 显示、MtrUtil 查询用） */
	public static void onWorldJoin() {
		clear();
		HiddenRoutesPackets.requestFullHiddenRoutesC2S();
	}
}
