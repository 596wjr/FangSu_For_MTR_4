package com.fangsu.ui;

import com.fangsu.Main;
import com.fangsu.mappings.ComponentHelper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MTR4 原生资源清单与旧版 NTE 轨道清单共用按来源排序的目录。 */
final class MtrRailModelCatalog {
    static final String BUILTIN = "fangsu:builtin_rail_models";
    private record Definition(String source, int order) { }

    private MtrRailModelCatalog() { }

    static Map<String, List<NteRailModelCatalog.Model>> load(Map<String, Component> models) {
        Map<String, Definition> definitions = readNativeDefinitions();
        // MTR 在原生资源之后转换旧版 rails JSON，同名 ID 以最后注册的模型为准。
        for (List<NteRailModelCatalog.Model> legacy : NteRailModelCatalog.load(models).values()) {
            for (NteRailModelCatalog.Model model : legacy) {
                if (!NteRailModelCatalog.OTHER.equals(model.source())) {
                    definitions.put(model.key(), new Definition(model.source(), model.order()));
                }
            }
        }
        definitions.put("default", new Definition(BUILTIN, -1));
        List<NteRailModelCatalog.Model> ordered = new ArrayList<>();
        for (Map.Entry<String, Component> entry : models.entrySet()) {
            Definition definition = definitions.getOrDefault(entry.getKey(), new Definition(NteRailModelCatalog.OTHER, Integer.MAX_VALUE));
            ordered.add(new NteRailModelCatalog.Model(entry.getKey(), entry.getValue(), definition.source(), definition.order()));
        }
        ordered.sort(Comparator.comparingInt((NteRailModelCatalog.Model model) -> model.source().equals(BUILTIN) ? 0 : model.source().equals(NteRailModelCatalog.OTHER) ? 2 : 1)
                .thenComparing(NteRailModelCatalog.Model::source, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(NteRailModelCatalog.Model::order)
                .thenComparing(NteRailModelCatalog.Model::key));
        Map<String, List<NteRailModelCatalog.Model>> result = new LinkedHashMap<>();
        for (NteRailModelCatalog.Model model : ordered) result.computeIfAbsent(model.source(), ignored -> new ArrayList<>()).add(model);
        return result;
    }

    static String sourceName(String source) {
        return BUILTIN.equals(source) ? ComponentHelper.translatableString("ui.fangsu.rail_model_tool.builtin") : NteRailModelCatalog.sourceName(source);
    }

    private static Map<String, Definition> readNativeDefinitions() {
        Map<String, Definition> result = new HashMap<>();
        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        ResourceLocation location = new ResourceLocation("mtr", "mtr_custom_resources.json");
        try {
            //#if MC_VERSION >= 11900
            List<Resource> resources = manager.getResourceStack(location);
            //#else
            //$$ List<Resource> resources = manager.getResources(location);
            //#endif
            for (Resource resource : resources) {
                //#if MC_VERSION >= 11900
                String source = location + " · " + resource.sourcePackId();
                try (Reader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                //#else
                //$$ String source = location + " · " + resource.getSourceName();
                //$$ try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                //#endif
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (!parsed.isJsonObject()) continue;
                    JsonObject root = parsed.getAsJsonObject();
                    // 旧版自定义列车/指示牌格式在 MTR 中不载入 rails。
                    if (root.has("custom_trains") || root.has("custom_signs") || !root.has("rails") || !root.get("rails").isJsonArray()) continue;
                    int order = 0;
                    for (JsonElement rail : root.getAsJsonArray("rails")) {
                        if (!rail.isJsonObject()) continue;
                        JsonElement id = rail.getAsJsonObject().get("id");
                        if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) {
                            result.put(id.getAsString(), new Definition(source, order++));
                        }
                    }
                } catch (Exception exception) {
                    Main.LOGGER.warn("[FangSu] Cannot read MTR rail source {}", source, exception);
                }
            }
        } catch (Exception exception) {
            Main.LOGGER.warn("[FangSu] Cannot list MTR rail sources", exception);
        }
        return result;
    }
}
