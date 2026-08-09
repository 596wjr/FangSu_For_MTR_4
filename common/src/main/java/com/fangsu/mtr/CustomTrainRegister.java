package com.fangsu.mtr;

import com.fangsu.Main;
import com.fangsu.utils.ResourceUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.architectury.event.events.client.ClientTickEvent;
import net.minecraft.resources.ResourceLocation;
import org.mtr.core.data.TransportMode;
import org.mtr.core.serializer.JsonReader;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.resource.ResourceProvider;
import org.mtr.mod.resource.VehicleResource;

import java.util.HashMap;
import java.util.Map;

/**
 * 把方速 custom_trains 注册为 MTR 4 可选车型（出现在 Siding 车辆选择 UI 中）。
 * <p>
 * MTR 4.0.5 的 {@code LegacyVehicleResource} 转换依赖 base_train_type 在 VEHICLES_CACHE 中命中，
 * 且文件含 custom_trains 时 lifts 会被丢弃，跨文件处理顺序不确定时会静默跳过且无日志——
 * 因此 custom_trains 放在 fangsu 命名空间、由本类自行转换注册：复制 MTR 内置 base 车型 JSON，
 * 按 custom_trains 覆盖 id/name/color/description 等字段（与 MTR 转换结果一致，保证
 * {@link LcdVehicleRegistry} 的 {@code mtr_custom_train_<key>_<variation>} ID 匹配），
 * 构造 {@link VehicleResource} 调 {@code CustomResourceLoader.registerVehicle()}
 * （public API，fromResourcePackCreator=true，不会写 MC 资源）。
 * <p>
 * 注册时机（关键）：实测 MTR 的 {@code CustomResourceLoader.reload()} listener 在方速的
 * reload listener 之后执行，而 reload() 开头会清空 VEHICLES——在 reload 流程内或主线程
 * 立即执行的注册都会被清掉（Minecraft.execute 在渲染线程是同步执行，不能用作延迟）。
 * 故采用 tick 轮询：资源重载时由 initResources 置 needsRegister 标志，CLIENT_POST tick 中
 * 等待 MTR reload 完成（内置 r_train_cab_1 在 VEHICLES_CACHE 中可见）后再注册，注册后
 * 验证生效才复位标志；若注册后被再次清空（如再次 F3+T），标志自动重新置位并重注册。
 */
public final class CustomTrainRegister {

    /** 变体后缀与显示名（与 MTR LegacyVehicleResource.Variation 一致） */
    private static final String[][] VARIANTS = {
            {"_trailer", " (Trailer)"},
            {"_cab_1", " Cab (Forwards)"},
            {"_cab_2", " Cab (Backwards)"},
            {"_cab_3", " Cab (Double)"},
    };

    /** 探测 id：MTR 内置基础车型变体，可见即代表 MTR CustomResourceLoader.reload() 已完成 */
    private static final String MTR_BASE_PROBE_ID = "r_train_cab_1";
    /** 探测 id：方速注册车型的首个变体，用于判断注册是否已生效 */
    private static final String OUR_PROBE_ID = "mtr_custom_train_r_train_fangsu_lcd_cab_1";

    /** 资源重载后置位，tick 轮询注册完成并验证后复位 */
    private static volatile boolean needsRegister = false;

    private CustomTrainRegister() {
    }

    /** 客户端初始化时调用一次，注册 tick 轮询（architectury 事件，双平台兼容） */
    public static void initTickHook() {
        ClientTickEvent.CLIENT_POST.register(client -> tick());
    }

    /** 资源重载（initResources）时调用：标记需要（重新）注册方速车辆 */
    public static void markNeedsRegister() {
        needsRegister = true;
    }

    private static void tick() {
        if (!needsRegister) return;
        try {
            // MTR reload 未完成则等下一 tick（getVehicleById 同步查 VEHICLES_CACHE，未注册时回调不触发）
            if (!isVehicleRegistered(MTR_BASE_PROBE_ID)) return;
            // 已在（前一轮注册成功或其他途径已注册）→ 完成
            if (isVehicleRegistered(OUR_PROBE_ID)) {
                needsRegister = false;
                return;
            }
            final int registered = register();
            // 注册后验证：注册数 0（如全部为暂缓注册的 LCD 车型）或探测命中 → 完成
            if (registered == 0 || isVehicleRegistered(OUR_PROBE_ID)) {
                needsRegister = false;
                if (registered > 0) {
                    Main.LOGGER.info("[FangSu] Custom train vehicles confirmed in MTR registry");
                }
            }
        } catch (Exception e) {
            Main.LOGGER.error("[FangSu] CustomTrainRegister tick failed", e);
        }
    }

    /** 同步查询 MTR VEHICLES_CACHE 中指定 id 的车辆是否已注册（不存在时回调不触发） */
    private static boolean isVehicleRegistered(String vehicleId) {
        final boolean[] found = {false};
        CustomResourceLoader.getVehicleById(TransportMode.TRAIN, vehicleId, pair -> {
            if (pair.left() != null) found[0] = true;
        });
        return found[0];
    }

    /**
     * 注册方速 custom_trains 车辆。失败只记日志不抛异常（initResources 整体已包 try-catch）。
     *
     * @return 实际注册的车辆数
     */
    public static int register() {
        try {
            final JsonObject root = ResourceUtil.loadAsJSON(new ResourceLocation("fangsu", "custom_trains.json")).getAsJsonObject();
            if (root == null || !root.isJsonObject() || !root.has("custom_trains")) {
                Main.LOGGER.info("[FangSu] No custom_trains in fangsu:custom_trains.json");
                return 0;
            }
            final JsonObject customTrains = root.getAsJsonObject("custom_trains");
            if (customTrains.size() == 0) return 0;

            // MTR 内置车型：mtr:mtr_custom_resources.json 经 loadAsJSON 合并后含内置 vehicles 数组
            final JsonObject builtinRoot = ResourceUtil.loadAsJSON(new ResourceLocation("mtr", "mtr_custom_resources.json")).getAsJsonObject();
            final Map<String, JsonObject> builtinById = new HashMap<>();
            if (builtinRoot != null && builtinRoot.isJsonObject() && builtinRoot.has("vehicles")) {
                for (JsonElement el : builtinRoot.getAsJsonArray("vehicles")) {
                    final JsonObject v = el.getAsJsonObject();
                    if (v.has("id")) builtinById.put(v.get("id").getAsString(), v);
                }
            }

            // 渲染时模型/纹理懒加载用（getCachedVehicleResource → provider.get），ResourceUtil 有缓存与容错
            final ResourceProvider provider = location -> {
                try {
                    return ResourceUtil.loadString(new ResourceLocation(location.getNamespace(), location.getPath()));
                } catch (Exception e) {
                    return null;
                }
            };

            int registered = 0;
            for (Map.Entry<String, JsonElement> entry : customTrains.entrySet()) {
                final String trainKey = entry.getKey();
                final JsonObject train = entry.getValue().getAsJsonObject();
                // LCD 车载渲染暂未修复（2026-08-09 搁置，排查笔记见 deliverables/mtr4-vehicle-lcd-notes.md）：
                // 带 lcd 字段的车型暂不注册（避免游戏中出现渲染异常的车辆），恢复时删除此行使之注册
                if (train.has("lcd")) continue;
                if (!train.has("base_train_type")) continue;
                final String baseType = train.get("base_train_type").getAsString();

                for (String[] variant : VARIANTS) {
                    final String baseId = baseType + variant[0];
                    final JsonObject base = builtinById.get(baseId);
                    if (base == null) {
                        Main.LOGGER.warn("[FangSu] custom_trains[{}] base vehicle {} not found, skipping", trainKey, baseId);
                        continue;
                    }

                    final JsonObject copy = base.deepCopy();
                    copy.addProperty("id", "mtr_custom_train_" + trainKey + variant[0]);
                    copy.addProperty("name", train.get("name").getAsString() + variant[1]);
                    if (train.has("color")) copy.addProperty("color", train.get("color").getAsString());
                    if (train.has("description")) copy.addProperty("description", train.get("description").getAsString());

                    // 纹理：texture_id 指向的资源存在才覆盖，否则沿用 base
                    // （方速 MTR3 遗留的 mtr:textures/entity/r_train 在 MTR4 已迁移为 mtr:textures/vehicle/r_train）
                    final String textureId = train.has("texture_id") ? train.get("texture_id").getAsString() : null;
                    if (textureId != null && !textureId.isEmpty() && ResourceUtil.hasResources(new ResourceLocation(textureId))) {
                        if (copy.has("models")) {
                            for (JsonElement m : copy.getAsJsonArray("models")) {
                                final JsonObject mo = m.getAsJsonObject();
                                final String tr = mo.has("textureResource") ? mo.get("textureResource").getAsString() : "";
                                if (!tr.contains("overlay")) mo.addProperty("textureResource", textureId);
                            }
                        }
                    }

                    // JsonReader 只接受 MTR 打包的 gson（org.mtr.libraries.*），经字符串中转
                    final org.mtr.libraries.com.google.gson.JsonElement mtrEl =
                            org.mtr.libraries.com.google.gson.JsonParser.parseString(copy.toString());
                    CustomResourceLoader.registerVehicle(new VehicleResource(new JsonReader(mtrEl), provider));
                    registered++;
                }
            }
            Main.LOGGER.info("[FangSu] Registered {} custom train vehicles", registered);
            return registered;
        } catch (Exception e) {
            Main.LOGGER.error("[FangSu] Failed to register custom trains", e);
            return 0;
        }
    }
}
