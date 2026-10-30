package com.fangsu.train;

import com.fangsu.utils.ResourceUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 独立读取 LCD 车型配置，支持 MTR4 vehicles 数组和旧 custom_trains 对象。
 * 资源包优先，无法读取时回退到模组 jar；由方速资源重载调用，不参与 JCM 的脚本重载。
 */
public final class LcdConfigSource {
    private LcdConfigSource() {
    }

    public static final String FANGSU_TRAINS = "fangsu:custom_trains.json";
    public static final String MTR_RESOURCES = "mtr:mtr_custom_resources.json";

    /** 一条带 lcd 的车型配置 */
    public record Entry(String key, JsonObject lcd, JsonObject raw) {
    }

    /**
     * 汇总所有来源里挂了 {@code lcd} 的车型，按「先 fangsu 后 mtr、后者不覆盖前者」合并。
     * key 为车型 key（fangsu 格式）或车型 ID（mtr 格式）。
     */
    public static Map<String, Entry> collect() {
        final Map<String, Entry> result = new LinkedHashMap<>();
        read(FANGSU_TRAINS, (key, lcd) -> result.putIfAbsent(key, new Entry(key, lcd, null)));
        read(MTR_RESOURCES, (key, lcd) -> result.putIfAbsent(key, new Entry(key, lcd, null)));
        return result;
    }

    /** 读取单个 json 里所有带 lcd 的车型；{@code consumer} 收到 (车型 key, lcd 对象) */
    public static void read(String path, BiConsumer<String, JsonObject> consumer) {
        final JsonObject root = readRoot(path);
        if (root == null) return;
        read(root, consumer);
    }

    /** 读整个根对象（拿不到返回 null） */
    public static JsonObject readRoot(String path) {
        try {
            final JsonElement parsed = readJson(path);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读一个 json 资源：资源包优先（这样能被资源包覆盖、且多个资源包会合并），
     * 资源包拿不到时回退到 classloader 直读 mod jar。
     * <p>
     * 兜底是必须的：资源加载早期，那时 {@code ResourceUtil} 还没拿到
     * ResourceManager，纯资源包读取会返回空对象（表现为「registry has 0 entries」每帧刷）。
     */
    private static JsonElement readJson(String path) {
        final int colon = path.indexOf(':');
        final ResourceLocation location = colon < 0
                ? new ResourceLocation("minecraft", path)
                : new ResourceLocation(path.substring(0, colon), path.substring(colon + 1));

        final JsonElement fromPacks = ResourceUtil.loadAsJSONWithModFallback(location);
        if (fromPacks != null && fromPacks.isJsonObject() && fromPacks.getAsJsonObject().size() > 0) {
            return fromPacks;
        }
        // 资源包完全不可用时（ResourceUtil 未初始化）直接读 jar
        final String jarText = ResourceUtil.readModResource(location);
        final JsonElement fromJar = ResourceUtil.parseJsonText(jarText);
        return fromJar != null ? fromJar : fromPacks;
    }

    /** 从一个已解析的根对象里提取带 lcd 的车型 */
    public static void read(JsonObject root, BiConsumer<String, JsonObject> consumer) {
        if (root == null) return;

        // MTR4 风格：vehicles 数组，条目自带 id
        if (root.has("vehicles") && root.get("vehicles").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("vehicles")) {
                if (!el.isJsonObject()) continue;
                final JsonObject vehicle = el.getAsJsonObject();
                if (!vehicle.has("lcd") || !vehicle.get("lcd").isJsonObject()) continue;
                final String id = vehicle.has("id") ? vehicle.get("id").getAsString() : null;
                if (id == null) continue;
                consumer.accept(id, vehicle.getAsJsonObject("lcd"));
            }
        }

        // MTR3/方速 风格：custom_trains 对象，key 即车型 key
        if (root.has("custom_trains") && root.get("custom_trains").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("custom_trains").entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                final JsonObject train = entry.getValue().getAsJsonObject();
                if (!train.has("lcd") || !train.get("lcd").isJsonObject()) continue;
                consumer.accept(entry.getKey(), train.getAsJsonObject("lcd"));
            }
        }
    }
}
