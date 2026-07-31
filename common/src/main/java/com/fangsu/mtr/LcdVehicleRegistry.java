package com.fangsu.mtr;

import com.fangsu.Main;
import com.fangsu.train.LcdInfo;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理车型 ID → LCD 配置的映射。
 * 从所有资源包的 mtr:mtr_custom_resources.json 中读取 custom_trains 条目，
 * 自动为每个有 lcd 配置的车型推导 MTR4 生成的 4 个变体 ID。
 */
public class LcdVehicleRegistry {

    private static final Map<String, LcdVehicleEntry> VEHICLE_LCD_MAP = new HashMap<>();

    /**
     * 在资源重载时调用。
     * 从所有资源包的 mtr:mtr_custom_resources.json 的 custom_trains 读取 LCD 配置。
     */
    public static void load() {
        VEHICLE_LCD_MAP.clear();

        try {
            // loadAsJSON 使用 getResourceStack 读取所有资源包并合并，
            // 这样方速的 custom_trains 会合并到 MTR 的 vehicles 旁，互不干扰
            final JsonElement jsonElement = com.fangsu.utils.ResourceUtil.loadAsJSON(
                    new net.minecraft.resources.ResourceLocation("mtr", "mtr_custom_resources.json"));
            if (jsonElement == null || !jsonElement.isJsonObject()) {
                Main.LOGGER.warn("[FangSu LCD] mtr:mtr_custom_resources.json not found");
                return;
            }

            final JsonObject root = jsonElement.getAsJsonObject();
            if (!root.has("custom_trains")) {
                Main.LOGGER.info("[FangSu LCD] No custom_trains section in mtr:mtr_custom_resources.json");
                return;
            }

            final JsonObject customTrains = root.getAsJsonObject("custom_trains");
            int count = 0;
            for (Map.Entry<String, JsonElement> entry : customTrains.entrySet()) {
                final String trainKey = entry.getKey();
                final JsonObject trainConfig = entry.getValue().getAsJsonObject();
                if (!trainConfig.has("lcd")) continue;

                final JsonObject lcdObj = trainConfig.getAsJsonObject("lcd");
                if (!lcdObj.has("id")) continue;

                final String lcdId = lcdObj.get("id").getAsString();
                final String slotsPath = lcdObj.has("slots") ? lcdObj.get("slots").getAsString() : null;

                // 为 MTR4 LegacyVehicleResource 生成的 4 个变体注册 LCD
                final String[] suffixes = {"_cab_1", "_cab_2", "_cab_3", "_trailer"};
                for (String suffix : suffixes) {
                    final String vehicleId = "mtr_custom_train_" + trainKey + suffix;

                    final JsonObject extra = new JsonObject();
                    extra.addProperty("id", lcdId);
                    if (slotsPath != null) extra.addProperty("slots", slotsPath);

                    final LcdInfo lcdInfo = LcdInfo.lazyFromJson(extra);
                    if (lcdInfo != null) {
                        VEHICLE_LCD_MAP.put(vehicleId, new LcdVehicleEntry(vehicleId, lcdInfo, lcdObj));
                        count++;
                    }
                }
            }
            Main.LOGGER.info("[FangSu LCD] Loaded {} LCD entries from custom_trains", count);
        } catch (Exception e) {
            Main.LOGGER.error("[FangSu LCD] Failed to load custom_trains", e);
        }

        Main.LOGGER.info("[FangSu LCD] Vehicle LCD registry has {} entries total", VEHICLE_LCD_MAP.size());
    }

    /**
     * 匹配车辆 ID 是否对应 LCD 车型。
     */
    public static LcdVehicleEntry match(String vehicleId) {
        return VEHICLE_LCD_MAP.get(vehicleId);
    }

    /**
     * 检查某车型 ID 是否已注册 LCD。
     */
    public static boolean hasLcd(String vehicleId) {
        return VEHICLE_LCD_MAP.containsKey(vehicleId);
    }

    public record LcdVehicleEntry(String vehicleId, LcdInfo lcdInfo, JsonObject rawConfig) {}

    /**
     * 在资源重载或世界重连时调用，使下一帧重新初始化所有 LCD 纹理。
     */
    public static void clearInitializedFlag() {
        initializedVehiclesSet.clear();
    }

    /** 跨 Mixin 的初始化标记集 */
    private static final java.util.HashSet<Long> initializedVehiclesSet = new java.util.HashSet<>();

    public static boolean isVehicleInitialized(long vehicleId) {
        return initializedVehiclesSet.contains(vehicleId);
    }

    public static void markVehicleInitialized(long vehicleId) {
        initializedVehiclesSet.add(vehicleId);
    }

    public static void printRegistered() {
        if (VEHICLE_LCD_MAP.isEmpty()) {
            Main.LOGGER.info("[FangSu LCD Debug]   (empty - no LCD vehicles registered)");
        } else {
            VEHICLE_LCD_MAP.keySet().forEach(id -> Main.LOGGER.info("[FangSu LCD Debug]   - {}", id));
        }
    }
}
