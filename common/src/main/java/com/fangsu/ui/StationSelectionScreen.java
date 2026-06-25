package com.fangsu.ui;

import com.fangsu.mappings.ComponentHelper;
import com.fangsu.utils.MtrUtil;
import org.mtr.core.data.Station;
import org.mtr.mod.client.MinecraftClientData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.List;
import java.util.function.Consumer;

public class StationSelectionScreen extends BaseSelectionScreen {
    private final Consumer<List<Long>> setter;
    private final BlockPos pos;
    private final Screen parent;

    public StationSelectionScreen(Component component, List<Long> defaultValue, Consumer<List<Long>> setter, BlockPos pos, int maxSelect, Screen parent) {
        super(component, 1, maxSelect);
        this.setter = setter;
        this.pos = pos;
        this.titles = new ArrayList<>();
        this.parent = parent;
        titles.add(ComponentHelper.translatable("ui.fangsu.common.selectStn"));
        titles.add(ComponentHelper.translatable("ui.fangsu.common.selected"));
    }

    @Override
    public void updateColumn() {
        final List<Station> raw = new ArrayList<>(MinecraftClientData.getInstance().stations);
        if (this.items != null)
            this.items.clear();
        else this.items = new ArrayList<>();
        final List<SelectionItem> items = new ArrayList<>();

        // 按距离从近到远排序
        final double px = pos.getX(), pz = pos.getZ();
        raw.sort(Comparator.comparingDouble(station -> {
            final double sx = (station.getMinX() + station.getMaxX()) / 2.0;
            final double sz = (station.getMinZ() + station.getMaxZ()) / 2.0;
            final double dx = sx - px, dz = sz - pz;
            return dx * dx + dz * dz;
        }));

        for (final Station station : raw) {
            items.add(new SelectionItem(
                    station.getName(),
                    Long.toString(station.getId()),
                    station.getColor()
            ));
        }
        this.items.add(items);
    }

    @Override
    public void onClose() {
        List<Long> v = new ArrayList<>();
        for (SelectionItem item : this.selectedItems) {
            v.add(Long.decode(item.value()));
        }
        this.setter.accept(v);
        Minecraft.getInstance().setScreen(parent);
    }
}
