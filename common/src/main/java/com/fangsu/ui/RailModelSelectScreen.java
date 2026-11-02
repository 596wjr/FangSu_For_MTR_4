package com.fangsu.ui;

import com.fangsu.items.ItemRailModelTool;
import com.fangsu.mappings.ComponentHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.resource.RailResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON 来源、轨道模型、已选模型三栏；关闭时保存完整的模型列表。 */
public class RailModelSelectScreen extends BaseSelectionScreen {
    private final ItemStack toolStack;
    private final Map<String, SelectionItem> models = new LinkedHashMap<>();
    private final Map<String, List<NteRailModelCatalog.Model>> sources;

    public RailModelSelectScreen(ItemStack toolStack) {
        super(ComponentHelper.translatable("ui.fangsu.rail_model_tool.title"), 2, Integer.MAX_VALUE);
        this.toolStack = toolStack;
        Map<String, Component> names = new LinkedHashMap<>();
        for (RailResource rail : CustomResourceLoader.getRails()) {
            models.put(rail.getId(), new SelectionItem(rail.getName(), rail.getId(), rail.getColor() | 0xFF000000));
            names.put(rail.getId(), ComponentHelper.literal(rail.getName()));
        }
        // 恢复一次即可；窗口缩放不会重新追加，也不丢弃暂时缺失的资源。
        for (String key : ItemRailModelTool.getModelKeys(toolStack)) {
            models.putIfAbsent(key, new SelectionItem(key, key, null));
            names.putIfAbsent(key, ComponentHelper.literal(key));
            SelectionItem item = models.get(key);
            if (!selectedItems.contains(item)) selectedItems.add(item);
        }
        sources = MtrRailModelCatalog.load(names);
        titles = List.of(ComponentHelper.translatable("ui.fangsu.nte_rail.sources"),
                ComponentHelper.translatable("ui.fangsu.nte_rail.models"),
                ComponentHelper.translatable("ui.fangsu.common.selected"));
        String initial = selectedItems.isEmpty() ? null : selectedItems.get(0).value();
        for (Map.Entry<String, List<NteRailModelCatalog.Model>> source : sources.entrySet()) {
            if (selected.get(0) == null) selected.set(0, source.getKey());
            if (source.getValue().stream().anyMatch(model -> model.key().equals(initial))) {
                selected.set(0, source.getKey());
                break;
            }
        }
    }

    @Override
    public void updateColumn() {
        List<SelectionItem> files = new ArrayList<>();
        List<SelectionItem> choices = new ArrayList<>();
        for (Map.Entry<String, List<NteRailModelCatalog.Model>> source : sources.entrySet()) {
            StringBuilder search = new StringBuilder(source.getKey());
            for (NteRailModelCatalog.Model model : source.getValue()) {
                search.append(' ').append(model.key()).append(' ').append(model.name().getString());
                if (source.getKey().equals(selected.get(0))) choices.add(models.get(model.key()));
            }
            files.add(new SelectionItem(MtrRailModelCatalog.sourceName(source.getKey()), source.getKey(), null, search.toString()));
        }
        items = List.of(files, choices);
    }

    @Override
    public void onClose() {
        List<String> keys = new ArrayList<>();
        for (SelectionItem item : selectedItems) {
            if (item != null && item.value() != null) keys.add(item.value());
        }
        ItemRailModelTool.setModelKeys(toolStack, keys);
        if (minecraft != null) minecraft.setScreen(null);
    }
}
