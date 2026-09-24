package com.fangsu.train;

import com.fangsu.Main;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一块车载 LCD 的配置：纹理布局（{@code slots.json} 的 {@code texArea}）
 * 与面板几何（{@code slots.json} 的 {@code pos} × {@code offsets}）。
 * <p>
 * 几何完全由 slots.json 决定，与旧路径 {@code DisplayHelper} 一致：
 * <ul>
 *   <li>{@code pos}：一个<b>顶点数组的数组</b>——每个元素是一组 4 个顶点的四边形；</li>
 *   <li>{@code offsets}：平移量列表，每个 offset 都把 {@code pos} 里的四边形整体再复制一份；</li>
 *   <li>顶点坐标即车厢局部坐标，与 MTR 的 OBJ 加载约定相同。</li>
 * </ul>
 */
public record LcdPanelConfig(String id,
                             int texWidth,
                             int texHeight,
                             List<Layout> layouts,
                             List<Panel> panels) {

    /** 纹理上的一块绘图区域（源自 slot 的 texArea） */
    public record Layout(String name, int x, int y, int w, int h) {
    }

    /**
     * 一个 slot 的面板：一块 texArea + 一组已经算好 offset 的四边形。
     *
     * @param name       slot 名（{@code lcd_door_left} 等），用于取 {@link Layout}，
     *                   也是交给 {@code MtrLcd.draw} 的 {@code side} 参数
     * @param quads      四边形列表，每个元素是 4 个顶点 {@code {x,y,z}}（已应用 offsets）
     * @param renderType 渲染阶段；取 slot 的 {@code renderType} 字段，缺省为 <b>{@code light}</b>
     */
    public record Panel(String name, List<double[][]> quads, String renderType) {
    }

    /** slot 未声明 renderType 时的默认渲染阶段（自发光，LCD 不依赖环境光） */
    public static final String DEFAULT_RENDER_TYPE = "light";

    public Layout layout(String name) {
        for (Layout layout : layouts) {
            if (layout.name().equals(name)) return layout;
        }
        return layouts.isEmpty() ? new Layout(name, 0, 0, texWidth, texHeight) : layouts.get(0);
    }

    /**
     * 从「已解析 slots 的 texSize + slots」构建。
     * 面板几何一律取 slot 自己的 {@code pos} × {@code offsets}。
     *
     * @param texWidthOverride  非正时使用 slots 里的 texSize
     * @param texHeightOverride 同上
     */
    public static LcdPanelConfig fromSlots(String id, JsonObject slotsInfo,
                                           int texWidthOverride, int texHeightOverride) {
        final List<Layout> layouts = new ArrayList<>();
        final List<Panel> panels = new ArrayList<>();

        int texWidth = texWidthOverride > 0 ? texWidthOverride : 1;
        int texHeight = texHeightOverride > 0 ? texHeightOverride : 1;

        if (slotsInfo != null) {
            final JsonArray texSize = slotsInfo.has("texSize") ? slotsInfo.getAsJsonArray("texSize") : null;
            if (texSize != null && texSize.size() >= 2) {
                if (texWidthOverride <= 0) texWidth = Math.max(1, texSize.get(0).getAsInt());
                if (texHeightOverride <= 0) texHeight = Math.max(1, texSize.get(1).getAsInt());
            }

            if (slotsInfo.has("slots")) {
                for (JsonElement el : slotsInfo.getAsJsonArray("slots")) {
                    if (!el.isJsonObject()) continue;
                    final JsonObject slot = el.getAsJsonObject();
                    final String name = slot.has("name") ? slot.get("name").getAsString() : "slot" + layouts.size();

                    if (slot.has("texArea")) {
                        final JsonArray area = slot.getAsJsonArray("texArea");
                        layouts.add(new Layout(name,
                                area.get(0).getAsInt(), area.get(1).getAsInt(),
                                area.get(2).getAsInt(), area.get(3).getAsInt()));
                    }

                    final List<double[][]> quads = parseQuads(slot);
                    if (quads.isEmpty()) {
                        Main.LOGGER.warn("[FangSu LCD] slot \"{}\" 没有可用的 pos 几何", name);
                        continue;
                    }
                    final String renderType = slot.has("renderType")
                            ? slot.get("renderType").getAsString()
                            : DEFAULT_RENDER_TYPE;
                    panels.add(new Panel(name, quads, renderType));
                }
            }
        }

        return new LcdPanelConfig(id, texWidth, texHeight, layouts, panels);
    }

    public static LcdPanelConfig fromSlots(String id, JsonObject slotsInfo) {
        return fromSlots(id, slotsInfo, 0, 0);
    }

    /**
     * 解析 slot 的几何：{@code pos}（若干组四边形）× {@code offsets}（平移量）。
     * 没有 offsets 时只按 pos 生成一份。
     */
    private static List<double[][]> parseQuads(JsonObject slot) {
        final List<double[][]> result = new ArrayList<>();
        if (!slot.has("pos") || !slot.get("pos").isJsonArray()) return result;

        // offsets 缺省为 [0,0,0]
        final List<double[]> offsets = new ArrayList<>();
        if (slot.has("offsets") && slot.get("offsets").isJsonArray()) {
            for (JsonElement oel : slot.getAsJsonArray("offsets")) {
                final JsonArray off = oel.getAsJsonArray();
                offsets.add(new double[]{off.get(0).getAsDouble(), off.get(1).getAsDouble(), off.get(2).getAsDouble()});
            }
        }
        if (offsets.isEmpty()) offsets.add(new double[]{0, 0, 0});

        for (JsonElement pel : slot.getAsJsonArray("pos")) {
            if (!pel.isJsonArray()) continue;
            final JsonArray positions = pel.getAsJsonArray();
            if (positions.size() < 4) continue;

            final double[][] base = new double[4][3];
            boolean valid = true;
            for (int i = 0; i < 4; i++) {
                final JsonElement vel = positions.get(i);
                if (!vel.isJsonArray()) {
                    valid = false;
                    break;
                }
                final JsonArray v = vel.getAsJsonArray();
                base[i][0] = v.get(0).getAsDouble();
                base[i][1] = v.get(1).getAsDouble();
                base[i][2] = v.get(2).getAsDouble();
            }
            if (!valid) continue;

            for (double[] off : offsets) {
                final double[][] quad = new double[4][3];
                for (int i = 0; i < 4; i++) {
                    quad[i][0] = base[i][0] + off[0];
                    quad[i][1] = base[i][1] + off[1];
                    quad[i][2] = base[i][2] + off[2];
                }
                result.add(quad);
            }
        }
        return result;
    }
}
