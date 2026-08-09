package com.fangsu.ui;

import com.fangsu.items.ItemRailModelTool;
import com.fangsu.mappings.ComponentHelper;
import net.minecraft.world.item.ItemStack;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.resource.RailResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 轨道模型选择界面——继承 {@link BaseSelectionScreen}，两列多选布局。
 * <p>
 * 左列：可选轨道模型（含"默认"）；右列：已选模型。点击切换，关闭时存入物品 NBT。
 */
public class RailModelSelectScreen extends BaseSelectionScreen {

    private final ItemStack toolStack;

    public RailModelSelectScreen(ItemStack toolStack) {
        super(ComponentHelper.translatable("ui.fangsu.rail_model_tool.title"), 1, Integer.MAX_VALUE);
        this.toolStack = toolStack;
        this.titles = new ArrayList<>();
        titles.add(ComponentHelper.translatable("ui.fangsu.rail_model_tool.title"));
        titles.add(ComponentHelper.translatable("ui.fangsu.common.selected"));
    }

    @Override
    protected void init() {
        super.init(); // 调 updateColumn() 填充 items
        // 从物品 NBT 恢复已选模型
        List<String> storedKeys = ItemRailModelTool.getModelKeys(toolStack);
        if (items != null && items.size() > 0) {
            for (String key : storedKeys) {
                for (SelectionItem item : items.get(0)) {
                    if (Objects.equals(item.value(), key)) {
                        selectedItems.add(item);
                        break;
                    }
                }
            }
        }
    }

    @Override
    public void updateColumn() {
        if (this.items == null) {
            this.items = new ArrayList<>();
        }
        this.items.clear();

        List<SelectionItem> railItems = new ArrayList<>();

        // 从 MTR4 CustomResourceLoader 获取所有已注册的轨道资源
        ObjectImmutableList<RailResource> rails = CustomResourceLoader.getRails();
        for (RailResource rail : rails) {
            railItems.add(new SelectionItem(
                    rail.getName(),
                    rail.getId(),
                    rail.getColor() | 0xFF000000
            ));
        }
        this.items.add(railItems);
    }

    @Override
    public void onClose() {
        List<String> keys = new ArrayList<>();
        for (SelectionItem item : this.selectedItems) {
            if (item != null && item.value() != null) {
                keys.add(item.value());
            }
        }
        ItemRailModelTool.setModelKeys(toolStack, keys);
        if (this.minecraft != null) {
            this.minecraft.setScreen(null);
        }
    }
}
