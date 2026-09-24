package com.fangsu.train;

import com.fangsu.Main;
import com.lx862.mtrscripting.core.ScriptManager;
import com.lx862.mtrscripting.core.primitive.ParsedScript;
import com.lx862.mtrscripting.core.primitive.ScriptContent;
import com.lx862.mtrscripting.mod.impl.mtr.MTRContentScripting;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.mtr.mapping.holder.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「带 {@code lcd} 的原生 MTR 车型」接进 JCM 的车辆脚本链路。
 * <p>
 * <b>用法</b>：在 {@code assets/mtr/mtr_custom_resources.json} 的 {@code vehicles[]} 里
 * （或者旧式的 {@code custom_trains} 对象里）给车型加一行：
 * <pre>
 * { "id": "r_train_fangsu_lcd", ..., "lcd": { "id": "mtr", "slots": "mtr:r_train/slots.json" } }
 * </pre>
 * 方速会自动把该车型接到 JCM 的脚本条目 {@value #SCRIPT_ENTRY_ID} 上，
 * 之后完全走 JCM 既有的链路：
 * <ol>
 *   <li>JCM {@code RenderVehiclesMixin} 按车型 ID 找到本脚本，建 {@code VehicleScriptInstance}；</li>
 *   <li>依次调用 {@code create / render / dispose}（见 {@link #FORWARD_SCRIPT}）；</li>
 *   <li>{@code render} 里 {@code ctx.drawCarModel(...)} 把面板模型排进
 *       <b>JCM 已经算好的车厢变换</b>，方速不需要拼矩阵。</li>
 * </ol>
 * <p>
 * <b>为什么要注入</b>：JCM 只从 {@code vehicles[].scriptId} 与 {@code vehicleScripts[]} 建立映射，
 * 它不认识 {@code lcd} 字段。所以在 JCM 资源重载收尾时
 * （{@code com.fangsu.mixin.jcm.MTRContentResourceManagerMixin}），
 * 方速扫一遍原生配置，把 {车型ID → fangsu_lcd} 补进 JCM 的两张私有静态表。
 * <p>
 * 车辆 ID 由 MTR 的 {@code getVehicleScriptEntryId} 解析：原生 MTR4 车型就是
 * {@code vehicles[].id}；旧式 {@code custom_trains} 的 key 会展开为
 * {@code mtr_custom_train_<key>_<cab_1|cab_2|cab_3|trailer>}。
 */
public final class JcmLcdScriptBridge {
    private JcmLcdScriptBridge() {
    }

    /** JCM 里这条伪脚本的条目 ID */
    public static final String SCRIPT_ENTRY_ID = "fangsu_lcd";

    /**
     * 伪脚本：三段式入口全部转发到 {@link VehicleLcdRenderer}。
     * <p>
     * 内容（纹理 + 面板模型）由 Java 侧准备；这里只负责把模型排进车厢变换。
     * 想在 JS 里自己画的话，转发脚本会在调用 {@code renderForTrain} 之前给
     * {@code state.draw} 一次机会覆盖整张纹理。
     */
    private static final String FORWARD_SCRIPT = String.join("\n",
            "\"use strict\";",
            "// 方速车载 LCD 转发脚本（JcmLcdScriptBridge 注入，不是资源文件）",
            "function create(ctx, state, vehicle) {",
            "    FangsuLcdManager.createForTrain(ctx, vehicle);",
            "}",
            "function render(ctx, state, vehicle) {",
            "    FangsuLcdManager.renderForTrain(ctx, vehicle);",
            "}",
            "function dispose(ctx, state, vehicle) {",
            "    FangsuLcdManager.disposeForTrain(ctx, vehicle);",
            "}");

    /** 缓存的脚本：资源重载之间复用，避免每次 reload 都新建 Rhino 作用域 */
    private static volatile ParsedScript cachedScript;

    /** 车型 ID → 该车型的 LCD 参数（slots 路径 / 面板几何 / 纹理尺寸） */
    private static final Map<String, LcdScriptConfig> CONFIGS = new HashMap<>();

    /** 车型 ID → 原始 lcd JSON（透传 script / extraConfig 用） */
    private static final Map<String, JsonObject> RAW_LCD = new HashMap<>();

    /** 车型 ID → JCM 脚本条目 ID（恒为 {@value #SCRIPT_ENTRY_ID}，保留结构便于将来多脚本） */
    private static final Map<String, String> SCRIPT_IDS = new HashMap<>();

    /** LCD 车型的参数 */
    public record LcdScriptConfig(List<String> slotPaths, JsonArray panels, int[] texSize) {
    }

    /** 已配置 LCD 的车辆 ID 集合 */
    public static java.util.Set<String> configuredVehicleIds() {
        return new java.util.HashSet<>(CONFIGS.keySet());
    }

    /** 该车型原始的 lcd JSON */
    public static JsonObject rawLcdFor(String vehicleId) {
        return RAW_LCD.get(vehicleId);
    }

    /** 由 JCM 的资源重载注入点调用 */
    public static void onJcmReload(Map<String, ParsedScript> scriptTable, Map<String, String> idTable) {
        JCM_SCRIPT_TABLE = scriptTable;
        JCM_ID_TABLE = idTable;
        try {
            refresh();
            if (CONFIGS.isEmpty()) {
                Main.debug("[FangSu LCD/JCM] 没发现带 lcd 的车型，跳过脚本注入");
                return;
            }
            scriptTable.put(SCRIPT_ENTRY_ID, script());
            idTable.putAll(SCRIPT_IDS);
            Main.LOGGER.info("[FangSu LCD/JCM] 已把 {} 个 LCD 车型接入 JCM 脚本 \"{}\"",
                    CONFIGS.size(), SCRIPT_ENTRY_ID);
        } catch (Throwable t) {
            Main.LOGGER.error("[FangSu LCD/JCM] 注入 LCD 脚本失败", t);
        }
    }

    /** JCM 的两张活表（reload 只 clear 不换对象，可长期持有） */
    private static volatile Map<String, ParsedScript> JCM_SCRIPT_TABLE;
    private static volatile Map<String, String> JCM_ID_TABLE;

    /**
     * 补注入：如果 JCM 重载时方速还读不到资源，脚本就没接上；
     * 由 tick 侧在资源就绪后再试一次（幂等）。已注入则直接返回。
     */
    public static void retryInject() {
        final Map<String, ParsedScript> scripts = JCM_SCRIPT_TABLE;
        final Map<String, String> ids = JCM_ID_TABLE;
        if (scripts == null || ids == null) return; // JCM 还没重载过
        if (scripts.get(SCRIPT_ENTRY_ID) != null && !CONFIGS.isEmpty()) return;
        onJcmReload(scripts, ids);
        com.fangsu.mtr.LcdVehicleRegistry.load();
    }

    /** 某车型的 LCD 参数（未配置返回 null） */
    public static LcdScriptConfig configFor(String vehicleId) {
        return CONFIGS.get(vehicleId);
    }

    public static boolean hasAny() {
        return !CONFIGS.isEmpty();
    }

    /** 车辆 ID 是否已被接入脚本链路 */
    public static boolean isScripted(String vehicleId) {
        return SCRIPT_IDS.containsKey(vehicleId);
    }

    /**
     * 重新扫描原生配置，建立「车型 ID → 脚本条目」映射。
     * 原生 MTR4 用 {@code vehicles[].id}；旧式 {@code custom_trains} 展开 4 个变体。
     */
    private static void refresh() {
        CONFIGS.clear();
        RAW_LCD.clear();
        SCRIPT_IDS.clear();
        cachedScript = null;

        // 原生 MTR4 配置：assets/mtr/mtr_custom_resources.json 的 vehicles[]
        collectNativeVehicles(true);
        // 旧式写法（MTR3 风格 / 方速 custom_trains）：key 需要展开变体
        collectCustomTrains(true);
        collectCustomTrains(false);
        collectNativeVehicles(false);
    }

    /** 原生 MTR4 车型：vehicles[] 里的条目，id 直接就是车辆 ID */
    private static void collectNativeVehicles(boolean mtrNamespace) {
        final JsonObject root = LcdConfigSource.readRoot(
                mtrNamespace ? LcdConfigSource.MTR_RESOURCES : LcdConfigSource.FANGSU_TRAINS);
        if (root == null || !root.has("vehicles") || !root.get("vehicles").isJsonArray()) return;

        for (JsonElement el : root.getAsJsonArray("vehicles")) {
            if (!el.isJsonObject()) continue;
            final JsonObject vehicle = el.getAsJsonObject();
            final JsonObject lcd = lcdOf(vehicle);
            if (lcd == null) continue;
            final String id = vehicle.has("id") ? vehicle.get("id").getAsString() : null;
            if (id == null) continue;
            put(id, lcd);
        }
    }

    /** 旧式 custom_trains 对象：key 展开为 MTR 生成的 4 个变体 ID */
    private static void collectCustomTrains(boolean mtrNamespace) {
        final JsonObject root = LcdConfigSource.readRoot(
                mtrNamespace ? LcdConfigSource.MTR_RESOURCES : LcdConfigSource.FANGSU_TRAINS);
        if (root == null || !root.has("custom_trains") || !root.get("custom_trains").isJsonObject()) return;

        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("custom_trains").entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            final JsonObject lcd = lcdOf(entry.getValue().getAsJsonObject());
            if (lcd == null) continue;
            for (String suffix : new String[]{"_cab_1", "_cab_2", "_cab_3", "_trailer"}) {
                put("mtr_custom_train_" + entry.getKey() + suffix, lcd);
            }
        }
    }

    private static JsonObject lcdOf(JsonObject vehicle) {
        if (vehicle == null || !vehicle.has("lcd") || !vehicle.get("lcd").isJsonObject()) return null;
        final JsonObject lcd = vehicle.getAsJsonObject("lcd");
        return lcd.has("id") ? lcd : null;
    }

    private static void put(String vehicleId, JsonObject lcd) {
        final List<String> slotPaths = new ArrayList<>();
        if (lcd.has("slots")) {
            for (int i = 0; i < 4; i++) slotPaths.add(lcd.get("slots").getAsString());
        }
        int[] texSize = null;
        if (lcd.has("texSize")) {
            final JsonArray arr = lcd.getAsJsonArray("texSize");
            texSize = new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt()};
        }
        CONFIGS.put(vehicleId, new LcdScriptConfig(slotPaths,
                lcd.has("panels") ? lcd.getAsJsonArray("panels").deepCopy() : null, texSize));
        RAW_LCD.put(vehicleId, lcd);
        SCRIPT_IDS.put(vehicleId, SCRIPT_ENTRY_ID);
    }

    private static ParsedScript script() {
        ParsedScript local = cachedScript;
        if (local != null) return local;

        final ScriptManager manager = MTRContentScripting.getScriptManager();
        if (manager == null) throw new IllegalStateException("JCM ScriptManager 还不可用");

        // 关键：解析之前必须先把 FangsuLcdManager 暴露进作用域（幂等，重复调用无副作用）
        LcdScriptExtension.install(manager);

        final JsonObject scriptInput = new JsonObject();
        scriptInput.addProperty("bridge", SCRIPT_ENTRY_ID);

        final List<ScriptContent> contents = new ArrayList<>();
        contents.add(new ScriptContent(new Identifier("fangsu", "internal/fangsu_lcd_input"),
                "const SCRIPT_INPUT = " + scriptInput + ";"));
        contents.add(new ScriptContent(new Identifier("fangsu", "internal/fangsu_lcd.js"), FORWARD_SCRIPT));

        local = manager.parseScript("FangSu Vehicle LCD (JCM bridge)", "Vehicle", contents);
        if (local == null) throw new IllegalStateException("方速 LCD 转发脚本解析失败");

        cachedScript = local;
        return local;
    }

    /** 调试用 */
    public static String debugInfo() {
        final StringBuilder sb = new StringBuilder("FangSu LCD/JCM: ").append(CONFIGS.size())
                .append(" vehicle(s), script=").append(cachedScript == null ? "(未解析)" : "已就绪");
        CONFIGS.forEach((id, cfg) -> sb.append("\n  - ").append(id)
                .append(" slots=").append(cfg.slotPaths())
                .append(" panels=").append(cfg.panels() == null ? 0 : cfg.panels().size()));
        return sb.toString();
    }
}
