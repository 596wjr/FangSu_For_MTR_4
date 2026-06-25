package com.fangsu.ui;

import com.fangsu.mappings.ComponentHelper;
import com.fangsu.mtr.LocalRoute;
import com.fangsu.utils.ColorUtil;
import com.fangsu.utils.MtrUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Station;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

public class RouteSelectionScreen extends BaseSelectionScreen {

    private final Consumer<List<RouteSelectInfo>> setter;
    private final BlockPos pos;
    private final Screen parent;

    private final Map<Long, Long> routePlatformMap = new HashMap<>();

    public RouteSelectionScreen(Component component, List<Long> defaultValue, Consumer<List<RouteSelectInfo>> setter, BlockPos pos, int maxSelect, Screen parent) {
        super(component, 2, maxSelect);
        this.setter = setter;
        this.pos = pos;
        this.titles = new ArrayList<>();
        this.parent = parent;
        titles.add(ComponentHelper.translatable("ui.fangsu.common.selectPlat"));
        titles.add(ComponentHelper.translatable("ui.fangsu.common.selectRoute"));
        titles.add(ComponentHelper.translatable("ui.fangsu.common.selected"));
    }

    @Override
    public void updateColumn() {
        final Station station = MtrUtil.getStationAt(MtrUtil.getCenterVector3f(pos));
        if (station != null) {
            if (this.items != null) {
                this.items.clear();
            } else {
                this.items = new ArrayList<>();
            }
            final Set<SelectionItem> platItemSet = new HashSet<>();
            final Set<SelectionItem> routeItemSet = new HashSet<>();
            final List<Platform> platforms = MtrUtil.getPlatformByStation(station);
            if (platforms != null) {
                for (final Platform p : platforms) {
                    final String dest = MtrUtil.getDestinationByPlatform(p);
                    platItemSet.add(new SelectionItem(
                            p.getName() + " -> " + dest,
                            String.valueOf(p.getId()),
                            null
                    ));
                }
            }
            if (!selected.isEmpty() && selected.get(0) != null) {
                final Long selectedPlat = Long.decode(selected.get(0));
                if (selectedPlat != null) {
                    final Platform p = MtrUtil.getPlatformById(selectedPlat);
                    if (p != null) {
                        final List<LocalRoute> routes = MtrUtil.getRouteByPlatform(p);
                        if (routes != null) {
                            for (final LocalRoute r : routes) {
                                routePlatformMap.put(r.id, p.getId());
                                final String dest = MtrUtil.getDestinationByRoute(r);
                                routeItemSet.add(new SelectionItem(
                                        r.name + " -> " + dest,
                                        String.valueOf(r.id),
                                        ColorUtil.awtColorToMinecraft(Color.decode(String.valueOf(r.color)))
                                ));
                            }
                        }
                    }
                }
            }
            final List<SelectionItem> itemPlat = new ArrayList<>(platItemSet);
            final List<SelectionItem> itemRoute = new ArrayList<>(routeItemSet);
            this.items.add(itemPlat);
            this.items.add(itemRoute);
        }
    }

    @Override
    public void onClose() {
        final List<RouteSelectInfo> v = new ArrayList<>();
        for (final SelectionItem item : this.selectedItems) {
            final Long routeId = Long.decode(item.value());
            v.add(new RouteSelectInfo(MtrUtil.getRouteById(routeId), MtrUtil.getPlatformById(routePlatformMap.get(routeId))));
        }
        this.setter.accept(v);
        Minecraft.getInstance().setScreen(parent);
    }
}