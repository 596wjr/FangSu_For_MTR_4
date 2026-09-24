package com.fangsu.mtr;

import com.fangsu.Main;
import com.fangsu.train.JcmLcdScriptBridge;
import com.fangsu.train.LcdInfo;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 「车辆 ID → LCD 配置」的运行期注册表。
 * <p>
 * 数据源是 {@link JcmLcdScriptBridge}：它已经从
 * {@code mtr:mtr_custom_resources.json} 的 {@code vehicles[]} / {@code custom_trains}
 * 以及 {@code fangsu:custom_trains.json} 里扫出所有带 {@code lcd} 的车型。
 * 本类只把扫描结果包成 {@link LcdInfo}，供渲染时按车辆 ID 取配置。
 */
public class LcdVehicleRegistry {

    private static final Map<String, LcdVehicleEntry> VEHICLE_LCD_MAP = new HashMap<>();

    /** 上次打印过的数量，用于抑制「每帧刷同一条」日志 */
    private static volatile int lastLoggedSize = -1;

    /**
     * 重建注册表（幂等）。由 JCM 资源重载触发
     * （{@code MTRContentResourceManagerMixin} → {@link JcmLcdScriptBridge#onJcmReload}）。
     */
    public static void load() {
        VEHICLE_LCD_MAP.clear();
        try {
            for (String vehicleId : JcmLcdScriptBridge.configuredVehicleIds()) {
                final JsonObject lcd = JcmLcdScriptBridge.rawLcdFor(vehicleId);
                if (lcd == null || !lcd.has("id")) continue;

                final JsonObject extra = new JsonObject();
                extra.addProperty("id", lcd.get("id").getAsString());
                if (lcd.has("slots")) extra.addProperty("slots", lcd.get("slots").getAsString());
                if (lcd.has("script")) extra.addProperty("script", lcd.get("script").getAsString());
                if (lcd.has("extraConfig")) extra.add("extraConfig", lcd.get("extraConfig"));

                final LcdInfo lcdInfo = LcdInfo.lazyFromJson(extra);
                if (lcdInfo == null) continue;
                VEHICLE_LCD_MAP.put(vehicleId, new LcdVehicleEntry(vehicleId, lcdInfo, lcd));
            }
        } catch (Exception e) {
            Main.LOGGER.error("[FangSu LCD] 载入 LCD 车型注册表失败", e);
        }

        final int total = VEHICLE_LCD_MAP.size();
        if (total != lastLoggedSize) {
            lastLoggedSize = total;
            Main.LOGGER.info("[FangSu LCD] Vehicle LCD registry has {} entries total", total);
        }
    }

    public static boolean hasAny() {
        return !VEHICLE_LCD_MAP.isEmpty();
    }

    /** 匹配车辆 ID 是否对应 LCD 车型 */
    public static LcdVehicleEntry match(String vehicleId) {
        if (vehicleId == null) return null;
        LcdVehicleEntry entry = VEHICLE_LCD_MAP.get(vehicleId);
        if (entry != null) return entry;
        // 扫描结果已经有了但注册表还没重建（或反之）时兜底一次
        if (VEHICLE_LCD_MAP.isEmpty() && JcmLcdScriptBridge.hasAny()) {
            load();
            entry = VEHICLE_LCD_MAP.get(vehicleId);
        }
        return entry;
    }

    public static boolean hasLcd(String vehicleId) {
        return match(vehicleId) != null;
    }

    public record LcdVehicleEntry(String vehicleId, LcdInfo lcdInfo, JsonObject rawConfig) {
    }

    // ===== 旧字段保留（旧路径已停用，但外部仍有引用） =====

    private static final java.util.HashSet<Long> initializedVehiclesSet = new java.util.HashSet<>();

    public static boolean isVehicleInitialized(long vehicleId) {
        return initializedVehiclesSet.contains(vehicleId);
    }

    public static void markVehicleInitialized(long vehicleId) {
        initializedVehiclesSet.add(vehicleId);
    }

    public static void unmarkVehicleInitialized(long vehicleId) {
        initializedVehiclesSet.remove(vehicleId);
    }

    public static void clearInitializedFlag() {
        initializedVehiclesSet.clear();
    }

    public static void printRegistered() {
        if (VEHICLE_LCD_MAP.isEmpty()) {
            Main.LOGGER.info("[FangSu LCD Debug]   (empty - no LCD vehicles registered)");
        } else {
            VEHICLE_LCD_MAP.keySet().forEach(id -> Main.LOGGER.info("[FangSu LCD Debug]   - {}", id));
        }
    }
}
