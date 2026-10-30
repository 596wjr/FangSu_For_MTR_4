package com.fangsu.mtr;

import com.fangsu.Main;
import com.fangsu.train.LcdConfigSource;
import com.google.gson.JsonElement;
import com.fangsu.train.LcdInfo;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/** 方速自己的车型 LCD 注册表；不读取或改写 JCM 的车辆脚本映射。 */
public class LcdVehicleRegistry {
    private static volatile Map<String, LcdVehicleEntry> entries = Map.of();

    /** 资源就绪后发布完整快照，渲染期间只查表，不重读 JSON。 */
    public static void load() {
        final Map<String, LcdVehicleEntry> loaded = new HashMap<>();
        collect(LcdConfigSource.readRoot(LcdConfigSource.MTR_RESOURCES), loaded);
        collect(LcdConfigSource.readRoot(LcdConfigSource.FANGSU_TRAINS), loaded);
        entries = Map.copyOf(loaded);
        Main.LOGGER.info("[FangSu LCD] 已加载 {} 个 LCD 车型（独立渲染）", entries.size());
    }

    private static void collect(JsonObject root, Map<String, LcdVehicleEntry> loaded) {
        if (root == null) return;
        if (root.has("custom_trains") && root.get("custom_trains").isJsonObject()) {
            root.getAsJsonObject("custom_trains").entrySet().forEach(entry -> {
                for (String suffix : new String[]{"_cab_1", "_cab_2", "_cab_3", "_trailer"}) {
                    put("mtr_custom_train_" + entry.getKey() + suffix, entry.getValue(), loaded);
                }
            });
        }
        if (root.has("vehicles") && root.get("vehicles").isJsonArray()) {
            for (JsonElement value : root.getAsJsonArray("vehicles")) {
                if (!value.isJsonObject()) continue;
                final JsonObject vehicle = value.getAsJsonObject();
                try {
                    if (vehicle.has("id")) put(vehicle.get("id").getAsString(), vehicle, loaded);
                } catch (Exception e) {
                    Main.LOGGER.error("[FangSu LCD] 无法读取车型 ID", e);
                }
            }
        }
    }

    private static void put(String id, JsonElement value, Map<String, LcdVehicleEntry> loaded) {
        if (!value.isJsonObject()) return;
        final JsonObject vehicle = value.getAsJsonObject();
        loaded.remove(id);
        if (!vehicle.has("lcd") || !vehicle.get("lcd").isJsonObject()) return;
        try {
            final JsonObject lcd = vehicle.getAsJsonObject("lcd").deepCopy();
            final LcdInfo info = LcdInfo.lazyFromJson(lcd);
            if (info == null) throw new IllegalArgumentException("lcd 缺少 id 或 slots");
            loaded.put(id, new LcdVehicleEntry(id, info, lcd));
        } catch (Exception e) {
            Main.LOGGER.error("[FangSu LCD] 车型 {} 的 LCD 配置无效，已跳过", id, e);
        }
    }

    public static boolean hasAny() {
        return !entries.isEmpty();
    }

    public static LcdVehicleEntry match(String vehicleId) {
        return vehicleId == null ? null : entries.get(vehicleId);
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
        if (entries.isEmpty()) {
            Main.LOGGER.info("[FangSu LCD Debug]   (empty - no LCD vehicles registered)");
        } else {
            entries.keySet().forEach(id -> Main.LOGGER.info("[FangSu LCD Debug]   - {}", id));
        }
    }
}
