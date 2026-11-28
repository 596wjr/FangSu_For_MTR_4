package com.fangsu.train.selection;

import com.fangsu.Main;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.ResourceManagerHelper;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TrainFormationPresets {
    private static final Identifier RESOURCE = new Identifier("mtr", "mtr_custom_resources.json");
    private static volatile Map<String, List<Item>> presets = Map.of();

    private TrainFormationPresets() {}

    public static void reload() {
        Map<String, List<Item>> loaded = new LinkedHashMap<>();
        ResourceManagerHelper.readAllResources(RESOURCE, stream -> {
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root.isJsonObject()) loaded.putAll(readPack(root.getAsJsonObject()));
            } catch (Exception exception) {
                Main.LOGGER.warn("Failed to read train formations from {}", RESOURCE, exception);
            }
        });
        presets = Map.copyOf(loaded);
    }

    static List<Item> get(String prefix) {
        return presets.get(prefix);
    }

    private static Map<String, List<Item>> readPack(JsonObject root) {
        Map<String, List<Item>> result = new LinkedHashMap<>();
        JsonElement definitions = root.get("train_formations");
        if (definitions == null) return result;
        if (!definitions.isJsonArray()) {
            Main.LOGGER.warn("train_formations in {} must be an array", RESOURCE);
            return result;
        }
        for (JsonElement element : definitions.getAsJsonArray()) {
            try {
                JsonObject definition = element.getAsJsonObject();
                String prefix = readId(definition, "prefix");
                if (result.containsKey(prefix)) continue;
                List<Item> items = new ArrayList<>();
                for (JsonElement rawItem : definition.getAsJsonArray("items")) {
                    JsonObject item = rawItem.getAsJsonObject();
                    items.add(new Item(readId(item, "id"), new CarPositionFilter(item)));
                }
                if (items.isEmpty()) throw new IllegalArgumentException("items must contain at least one car rule");
                result.put(prefix, List.copyOf(items));
            } catch (RuntimeException exception) {
                Main.LOGGER.warn("Invalid train formation in {}: {}", RESOURCE, exception.getMessage());
            }
        }
        return result;
    }

    private static String readId(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(key + " must be a non-empty string");
        }
        return value.getAsString().strip();
    }

    record Item(String id, CarPositionFilter filter) {}
}
