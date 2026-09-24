package com.fangsu.train;

import com.fangsu.utils.ResourceUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * LCD 车型配置的来源解析。
 * <p>
 * 历史上 LCD 配置写在 {@code fangsu:custom_trains.json}（方速自定义车型注册用的文件）。
 * 现在允许两种写法，本类统一读取：
 * <ol>
 *   <li>{@code fangsu:custom_trains.json} → {@code custom_trains} 对象（旧格式，key 即车型 key）；</li>
 *   <li>{@code mtr:mtr_custom_resources.json} → {@code custom_trains} 对象（MTR3 风格）
 *       或 {@code vehicles} 数组（MTR4 风格，{@code id} 即车型 ID）——
 *       只要条目上挂了 {@code lcd} 就会被识别。</li>
 * </ol>
 * <b>读取方式</b>：统一用 MTR 的 {@link ResourceManagerHelper#readResource(Identifier)}，
 * 与 MTR 自己注册车型时走的是同一条路，不依赖 {@code ResourceUtil} 的初始化时序
 * （后者在 JCM 车辆脚本重载阶段可能还没拿到 ResourceManager，会刷
 * {@code No resources found for: fangsu:custom_trains.json}）。
 * <p>
 * 每次调用直读（不做缓存）：这两个文件很小，而调用方本来就是低频的
 * （资源重载 / tick 补注入），换掉 ResourceUtil 的缓存反而少一层「缓存过期」坑。
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
     * 兜底是必须的：JCM 车辆脚本重载发生得很早，那时 {@code ResourceUtil} 还没拿到
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
