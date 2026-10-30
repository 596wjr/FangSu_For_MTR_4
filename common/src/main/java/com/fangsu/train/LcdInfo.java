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
        final LcdInfo lazy = lazyFromJson(json);
        if (lazy == null) throw new IllegalArgumentException("lcd 缺少 id/script 或 slots");
        String id = lazy.id();
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
        if (!json.has("slots")) return null;
        final boolean scripted = json.has("script") && !json.get("script").getAsString().isBlank();
        if (!json.has("id") && !scripted) return null;
        // 保留文档中的脚本调用方式：仅填 script 时无需注册 Java LCD id。
        String id = json.has("id") ? json.get("id").getAsString() : "mtr";
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

    /** 是否配置了 JS 脚本（lcd.script 非空）。配置了脚本则优先走 JS 绘制路径 */
    public boolean hasScript() {
        return extra != null && extra.has("script") && !extra.get("script").getAsString().isBlank();
    }

    /** 脚本资源路径（ResourceLocation 字符串） */
    public String script() {
        return extra.get("script").getAsString();
    }

    /** lcd.extraConfig 配置对象，未配置时返回 null */
    public JsonObject scriptExtraConfigJson() {
        return extra != null && extra.has("extraConfig") ? extra.getAsJsonObject("extraConfig") : null;
    }
}
