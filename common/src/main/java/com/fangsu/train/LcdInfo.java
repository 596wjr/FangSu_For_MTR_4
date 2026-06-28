package com.fangsu.train;

import com.fangsu.Main;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.ResourceManagerHelper;

public record LcdInfo(String id, JsonObject slotsInfo, JsonObject extra) {

    /**
     * 从JSON解析完整的LcdInfo（含slots）。
     * 仅在渲染阶段调用，避免资源加载时与MTR冲突。
     */
    public static LcdInfo fromJson(JsonObject json) throws Exception {
        if (!json.has("id") || !json.has("slots")) throw new IllegalArgumentException();
        String id = json.get("id").getAsString();
        String slots = json.get("slots").getAsString();
        Identifier slotsIdentifier = new Identifier(slots);
        String slotsJsonStr = ResourceManagerHelper.readResource(slotsIdentifier);
        if (slotsJsonStr.isEmpty()) {
            throw new IllegalArgumentException("Slots JSON not found: " + slots);
        }
        JsonObject slotsJson = Main.JSON_PARSER.parse(slotsJsonStr).getAsJsonObject();
        return new LcdInfo(id, slotsJson, json);
    }

    /**
     * 资源加载阶段使用：不解析slots，只存原始JSON。
     * 后续调用resolveSlots()完成解析。
     */
    public static LcdInfo lazyFromJson(JsonObject json) {
        if (!json.has("id") || !json.has("slots")) return null;
        String id = json.get("id").getAsString();
        return new LcdInfo(id, null, json);
    }

    /**
     * 延迟解析slots JSON（在渲染阶段调用）。
     */
    public LcdInfo resolveSlots() {
        if (slotsInfo != null) return this;
        if (!extra.has("slots")) return this;
        try {
            String slots = extra.get("slots").getAsString();
            Identifier slotsIdentifier = new Identifier(slots);
            String slotsJsonStr = ResourceManagerHelper.readResource(slotsIdentifier);
            if (!slotsJsonStr.isEmpty()) {
                JsonObject slotsJson = Main.JSON_PARSER.parse(slotsJsonStr).getAsJsonObject();
                return new LcdInfo(id, slotsJson, extra);
            }
        } catch (Exception e) {
            Main.LOGGER.error("[FangSu LCD] Failed to resolve slots for: " + id, e);
        }
        return this;
    }
}
