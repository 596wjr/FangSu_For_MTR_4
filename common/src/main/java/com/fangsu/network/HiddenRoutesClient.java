package com.fangsu.network;

import com.fangsu.Main;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * JCM 脚本数据合并桥（客户端）。
 * <p>
 * mtrscripting（JCM 内含库）不是 FangSu 的编译依赖（fabric 构建不声明 JCM），
 * 运行时用 Class.forName 探测 + 反射调用（{@link com.fangsu.MainClient#is_nte_loaded} 同款模式）：
 * {@code new MTRDatasetHolder(json)}（TSC 反序列化 SimplifiedRoute 数组）
 * + {@code VehicleDataCache.putMTRDataCache(holder)}（公开静态，addFrom + 重建 routeIdMap）。
 * 探测失败（未装 JCM）时空转——没有包会到达，无副作用。
 */
public final class HiddenRoutesClient {

	private static boolean available = false;
	private static Constructor<?> holderCtor;
	private static Method putMTRDataCache;

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
			Main.LOGGER.info("[FangSu] JCM (mtrscripting) 未加载，隐藏线路通道停用");
		}
	}

	/** S2C 响应：JSON（{"routes":[...]}）→ MTRDatasetHolder → VehicleDataCache.mtrData */
	public static void mergeHiddenRoutes(String json) {
		if (!available) return;
		try {
			final Object holder = holderCtor.newInstance(json);
			putMTRDataCache.invoke(null, holder);
		} catch (Exception e) {
			Main.LOGGER.error("[FangSu] 隐藏线路数据合并失败", e);
		}
	}
}
