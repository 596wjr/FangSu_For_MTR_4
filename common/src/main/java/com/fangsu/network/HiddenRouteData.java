package com.fangsu.network;

import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.SimplifiedRoutePlatform;
import org.mtr.core.data.Station;
import org.mtr.core.serializer.JsonWriter;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonObject;

import java.util.List;

/**
 * 隐藏线路的 SimplifiedRoute 投影（仅服务端使用）。
 * <p>
 * SimplifiedRoute 的构造器是 private（final 类），服务端无法直接 new；
 * 故按 simplifiedRoute schema（id/name/color/circularState/platforms）手工组装 JSON，
 * 平台部分用公开构造器 {@link SimplifiedRoutePlatform#SimplifiedRoutePlatform(long, long, String, String)}
 * + 继承的 {@code serializeFullData} 精确序列化（含基类 stationName 字段），
 * 与 TSC 客户端反序列化（SimplifiedRoute(ReaderBase)）严格对位。
 * circularState 的 wire 格式为枚举名（codegen 读侧经 EnumHelper.valueOf 按 name() 解析）。
 * <p>
 * 只回传「被请求且隐藏」的线路：请求列表由 JCM 侧站序数据驱动（车辆实际运行的线路 id），
 * 隐藏线路只进入 JCM 脚本数据面，MTR 主通道（MinecraftClientData）不受影响。
 */
public final class HiddenRouteData {

	private HiddenRouteData() {
	}

	/** 把请求列表里「存在且隐藏」的线路投影为 JSON 数组 */
	public static JsonArray buildRoutesJson(Simulator simulator, List<Long> routeIds) {
		final JsonArray routes = new JsonArray();
		for (long id : routeIds) {
			final Route route = simulator.routeIdMap.get(id);
			if (route != null && route.getHidden()) {
				routes.add(toRouteJson(route));
			}
		}
		return routes;
	}

	/** 全量投影：所有隐藏线路（世界进入时方速 PIDS/RIS 显示用，不依赖 JCM 触发） */
	public static JsonArray buildAllHiddenRoutesJson(Simulator simulator) {
		final JsonArray routes = new JsonArray();
		simulator.routeIdMap.values().forEach(route -> {
			if (route.getHidden()) {
				routes.add(toRouteJson(route));
			}
		});
		return routes;
	}

	private static JsonObject toRouteJson(Route route) {
		final JsonObject root = new JsonObject();
		root.addProperty("id", route.getId());
		root.addProperty("name", route.getName());
		root.addProperty("color", route.getColor() & 0xFFFFFF);
		root.addProperty("circularState", route.getCircularState().name());
		final JsonArray platforms = new JsonArray();
		for (int i = 0; i < route.getRoutePlatforms().size(); i++) {
			final RoutePlatformData rpd = route.getRoutePlatforms().get(i);
			// 平台解析与 TSC SimplifiedRoute 私有构造器一致（public 字段 platform，null 时回退 0/""）
			final Platform platform = rpd.platform;
			final Station station = platform == null ? null : platform.area;
			final SimplifiedRoutePlatform srp = new SimplifiedRoutePlatform(
					platform == null ? 0 : platform.getId(),
					station == null ? 0 : station.getId(),
					route.getDestination(i),
					station == null ? "" : station.getName());
			final JsonObject platformJson = new JsonObject();
			srp.serializeFullData(new JsonWriter(platformJson));
			// 换乘颜色：复刻 TSC SimplifiedRoute 构造器的 addInterchangeRoutes，只收集非隐藏线路。
			// 字段名与 wire 结构来自 interchangeColorsForStationName schema，
			// 客户端 SimplifiedRoutePlatform(ReaderBase) 反序列化后 forEach 直接可用
			platformJson.add("interchangeRouteNamesForColorList", buildInterchanges(route.getColor(), platform, station));
			platforms.add(platformJson);
		}
		root.add("platforms", platforms);
		return root;
	}

	/**
	 * 本站点的换乘线路（只含非隐藏、颜色不同的线路，与 TSC {@code SimplifiedRoute#addInterchangeRoutes} 一致）：
	 * 站非 null 时收集全站台台的线路，否则仅当前站台线路。
	 */
	private static JsonArray buildInterchanges(int thisColor, Platform platform, Station station) {
		final java.util.TreeMap<Integer, java.util.List<String>> interchanges = new java.util.TreeMap<>();
		if (station != null) {
			station.savedRails.forEach(stationPlatform -> collectInterchangeRoutes(thisColor, stationPlatform.routes, interchanges));
		} else if (platform != null) {
			collectInterchangeRoutes(thisColor, platform.routes, interchanges);
		}
		final JsonArray list = new JsonArray();
		interchanges.forEach((color, names) -> {
			final JsonObject entry = new JsonObject();
			entry.addProperty("color", color);
			final JsonArray routeNames = new JsonArray();
			names.stream().distinct().forEach(routeNames::add); // 原版 addRouteName 的 contains 去重语义
			entry.add("routeNames", routeNames);
			list.add(entry);
		});
		return list;
	}

	private static void collectInterchangeRoutes(int thisColor, Iterable<Route> routes, java.util.TreeMap<Integer, java.util.List<String>> interchanges) {
		routes.forEach(interchangeRoute -> {
			if (!interchangeRoute.getHidden() && interchangeRoute.getColor() != thisColor) {
				interchanges.computeIfAbsent(interchangeRoute.getColor(), key -> new java.util.ArrayList<>())
						.add(interchangeRoute.getName().split("\\|\\|")[0]);
			}
		});
	}
}
