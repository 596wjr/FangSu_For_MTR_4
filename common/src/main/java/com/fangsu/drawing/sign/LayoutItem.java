package com.fangsu.drawing.sign;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class LayoutItem extends SignItem {

    protected final Map<String, List<SignItem>> childLanes = new LinkedHashMap<>();

    protected LayoutItem(JsonObject json) {
        for (String laneKey : getLaneKeys()) {
            childLanes.put(laneKey, parseLane(json, laneKey));
        }
    }

    public abstract List<String> getLaneKeys();

    public List<SignItem> getLane(String key) {
        return childLanes.computeIfAbsent(key, k -> new ArrayList<>());
    }

    protected List<SignItem> parseLane(JsonObject json, String key) {
        List<SignItem> lane = new ArrayList<>();
        if (json == null || !json.has(key) || !json.get(key).isJsonArray()) {
            return lane;
        }
        JsonArray array = json.getAsJsonArray(key);
        for (JsonElement e : array) {
            if (!e.isJsonObject()) continue;
            JsonObject obj = e.getAsJsonObject();
            if (!obj.has("type") || !obj.get("type").isJsonPrimitive()) continue;
            String type = obj.getAsJsonPrimitive("type").getAsString();
            lane.add(SignItemFactory.get(type).apply(obj));
        }
        return lane;
    }

    @Override
    protected JsonObject saveToJson() {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, List<SignItem>> entry : childLanes.entrySet()) {
            JsonArray array = new JsonArray();
            for (SignItem item : entry.getValue()) {
                array.add(item.toJson());
            }
            json.add(entry.getKey(), array);
        }
        return json;
    }

    protected float getLaneWidth(Graphics2D g, String laneKey, float unit) {
        float laneUnit = unit / 2f;
        float total = 0;
        List<SignItem> lane = getLane(laneKey);
        for (SignItem token : lane) {
            total += token.getWidth(g, laneUnit) + laneUnit * 0.1f;
        }
        return total;
    }

    protected void drawLane(SignDrawContext ctx, String laneKey, float x, float y, float laneUnit, boolean selected) {
        Graphics2D g = ctx.graphics();
        float drawX = x;
        for (SignItem item : getLane(laneKey)) {
            float w = item.getWidth(g, laneUnit);
            item.draw(new SignDrawContext(g, drawX, y, laneUnit, 0, selected));
            drawX += w + laneUnit * 0.1f;
        }
    }

    /**
     * 布局容器自身始终被视为"已创建"，但它的就绪/完成状态应取决于其所有子 item。
     * 这样当子项（例如路线项）因 MTR 数据未同步而未就绪时，容器整体也不应被视为就绪，
     * 从而避免提前绘制"未命名"线路。
     */
    @Override
    public boolean isReady() {
        for (List<SignItem> lane : childLanes.values()) {
            for (SignItem item : lane) {
                if (!item.isReady()) return false;
            }
        }
        return true;
    }

    @Override
    public boolean isCompleted() {
        for (List<SignItem> lane : childLanes.values()) {
            for (SignItem item : lane) {
                if (!item.isCompleted()) return false;
            }
        }
        return true;
    }
}
