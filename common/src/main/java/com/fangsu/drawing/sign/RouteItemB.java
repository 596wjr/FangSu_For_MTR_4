package com.fangsu.drawing.sign;

import com.fangsu.mappings.ComponentHelper;
import com.fangsu.extraConfig.ConfigEntry;
import com.fangsu.extraConfig.ConfigSpec;
import com.fangsu.extraConfig.RunnableConfig;
import com.fangsu.mtr.LocalRoute;
import com.fangsu.scripting.G2dTextHelper;
import com.fangsu.scripting.RouteNameUtil;
import com.fangsu.scripting.TextUtil;
import com.fangsu.ui.RouteSelectionScreen;
import com.fangsu.utils.ColorUtil;
import com.fangsu.utils.MtrUtil;
import com.fangsu.utils.ResourceUtil;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import com.fangsu.mappings.ResourceLocation;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class RouteItemB extends SignItem {
    private LocalRoute route;
    private long routeId = -1; // 从JSON解析的原始路线ID，用于在route为null时保留数据
    private Font font;

    public RouteItemB(JsonObject json) {
        if (json.has("route") && json.get("route").isJsonPrimitive()) {
            routeId = json.getAsJsonPrimitive("route").getAsLong();
            route = MtrUtil.getRouteById(routeId);
        } else {
            route = null;
            routeId = -1;
        }
        font = ResourceUtil.loadFont(new ResourceLocation("fangsu:fonts/source-han-sans-bold.otf").getRaw());
    }

    @Override
    protected JsonObject saveToJson() {
        JsonObject json = new JsonObject();
        if (route != null) {
            json.addProperty("route", route.id);
        } else if (routeId != -1) {
            // 路线对象未加载成功时，保留原始ID
            json.addProperty("route", routeId);
        }
        return json;
    }

    @Override
    public String getType() {
        return "routeb";
    }

    @Override
    public float getWidth(Graphics2D g, float unit) {
        String routeName = getRouteName();
        boolean isNumLine = RouteNameUtil.isNumLine(routeName);
        float width = unit * 0.6f;
        if (isNumLine) {
            String name = RouteNameUtil.getCJKLineName(TextUtil.getCjkParts(routeName));
            width += G2dTextHelper.getUnifiedStringWidth(g, font, name, unit * 0.8f);
            width += G2dTextHelper.getMultiLinesWidth(g, font, unit * 0.7f, "号线", TextUtil.getNonCjkParts(routeName));
        } else {
            width += G2dTextHelper.getMultiLinesWidth(g, font, unit * 0.8f, TextUtil.getNonExtraParts(routeName).split("\\|"));
        }
        return width;
    }

    @Override
    public boolean isReady() {
        return resolveRouteIfNeeded();
    }

    @Override
    public boolean isCompleted() {
        return isReady();
    }

    /**
     * 若选择了线路（routeId != -1）但线路对象尚未就绪（MTR 数据可能未同步），
     * 则尝试重新解析。返回该线路当前是否可用于绘制。
     * <p>
     * - 未选择线路（routeId == -1）：视为就绪，绘制"未命名"；
     * - 选择了线路且已解析成功：就绪；
     * - 选择了线路但客户端尚无任何路线数据（可能尚未同步）：未就绪，等待重绘；
     * - 选择了线路但客户端已有路线数据却仍查不到（线路不存在）：视为就绪，绘制"未命名"。
     */
    private boolean resolveRouteIfNeeded() {
        if (route != null) return true;
        if (routeId == -1) return true; // 未选择线路，属正常"未命名"
        // 重新尝试解析（MTR 数据可能刚刚同步）
        final LocalRoute resolved = MtrUtil.getRouteById(routeId);
        if (resolved != null) {
            route = resolved;
            return true;
        }
        // 客户端尚无任何路线数据：可能尚未同步，继续等待重绘
        if (!MtrUtil.hasAnyRouteData()) return false;
        // 已存在其他路线却查不到指定 id：视为线路不存在，绘制"未命名"
        return true;
    }

    @Override
    public void draw(SignDrawContext ctx) {
        Graphics2D g = ctx.graphics();
        float u = ctx.unit();
        int x = (int) ctx.x();
        int y = (int) ctx.y();
        float width = getWidth(g, u);
        Color c = getRouteColor();
        String routeName = getRouteName();
        boolean isNumLine = RouteNameUtil.isNumLine(routeName);
        g.setColor(c);
        g.fillRoundRect(x, y, (int) width, (int) u, (int) (u / 10), (int) (u / 10));
        g.setColor(ColorUtil.isLightColor(c) ? Color.BLACK : Color.WHITE);
        if (isNumLine) {
            int currentX = x + (int) (u * 0.25f);
            String name = RouteNameUtil.getCJKLineName(TextUtil.getCjkParts(routeName));
            currentX += G2dTextHelper.drawStrUnified(g, font, name, currentX, (int) (y + u * 0.8f), u * 0.8f, 0);
            currentX += G2dTextHelper.drawStrMultiLines(g, font, currentX, y + (int) (u * 0.15f) - (int) (u * 0.75f), (int) (u * 0.75f), 0, "号线", TextUtil.getNonCjkParts(routeName));
        } else {
            G2dTextHelper.drawStrMultiLines(g, font, (int) (x + u * 0.25f), y + (int) (u * 0.125f) - (int) (u * 0.8f), (int) (u * 0.8f), 1, TextUtil.getNonExtraParts(routeName).split("\\|"));
        }
    }

    @Override
    public ResourceLocation getIconLocation() {
        return new ResourceLocation("fangsu:sign/routeb.png");
    }

    @Override
    public List<ConfigEntry<?>> getConfigs() {
        List<ConfigEntry<?>> list = new ArrayList<ConfigEntry<?>>();
        list.add(new RunnableConfig(
                ComponentHelper.translatable("ui.fangsu.common.selectRoute"),
                new ConfigSpec("func"),
                () -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) {
                        mc.setScreen(new RouteSelectionScreen(
                                ComponentHelper.translatable("ui.fangsu.common.selectRoute"),
                                List.of(),
                                (v) -> {
                                    if (v != null && !v.isEmpty())
                                        route = v.get(0).route;
                                },
                                mc.player.getOnPos(), 1, Minecraft.getInstance().screen));
                    }
                }
        ));
        return list;
    }

    private String getRouteName() {
        if (route == null) return "未命名|Undefined";
        return route.name;
    }

    private Color getRouteColor() {
        if (route == null) return new Color(0xabcdef);
        return new Color(route.color);
    }
}
