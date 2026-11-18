package com.fangsu.mixin;

import com.fangsu.mappings.ComponentHelper;
import com.fangsu.mtr.DrawableRoute;
import com.fangsu.mtr.RouteStopStatus;
import com.fangsu.mtr.RouteEditorAccess;
import com.fangsu.mtr.RouteServices;
import com.fangsu.mtr.ServiceStop;
import com.fangsu.scripting.TextUtil;
import com.fangsu.ui.ConfigChoiceScreen;
import org.mtr.core.data.Route;
import org.mtr.mod.screen.EditRouteScreen;
import org.mtr.mod.screen.DashboardScreen;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mapping.mapper.ScreenExtension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import org.mtr.mapping.holder.ClickableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

@Mixin(value = EditRouteScreen.class, remap = false)
public abstract class EditRouteServiceMixin {
    @Unique private Route fangsu$parentRoute;
    @Unique private DashboardScreen fangsu$dashboard;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fangsu$captureRoute(Route route, ScreenExtension parent, CallbackInfo ci) {
        fangsu$parentRoute = route;
        fangsu$dashboard = parent instanceof DashboardScreen dashboard ? dashboard : null;
    }

    @Inject(method = "init2", at = @At("RETURN"))
    private void fangsu$addServiceActions(CallbackInfo ci) {
        if (fangsu$dashboard == null) {
            return;
        }
        final var relation = RouteServices.relation(fangsu$parentRoute);
        if (relation != null) {
            final var parent = MinecraftClientData.getDashboardInstance().routeIdMap.get(relation.parentRoute());
            if (!relation.express() && parent != null && parent.getRoutePlatforms().size() >= 2) {
                fangsu$addAction(12, 120, "ui.fangsu.route.change_section",
                        () -> fangsu$selectSection(parent, fangsu$parentRoute));
            }
            return;
        }
        if (fangsu$parentRoute.getRoutePlatforms().size() >= 2 && MinecraftClientData.getDashboardInstance().routes.stream().noneMatch(route -> {
            final var child = RouteServices.relation(route);
            return child != null && child.parentRoute() == fangsu$parentRoute.getId();
        })) {
            fangsu$addAction(((ScreenExtension) (Object) this).getWidthMapped() - 120, 108,
                    "ui.fangsu.route.import", this::fangsu$chooseImportType);
        }
        final String direction = TextUtil.getExtraParts(fangsu$parentRoute.getName());
        if (direction.isEmpty() || direction.contains("||") || fangsu$parentRoute.getRoutePlatforms().size() < 2) {
            return;
        }

        fangsu$addAction(12, 90, "ui.fangsu.route.express", () -> fangsu$configure(false));
        fangsu$addAction(106, 90, "ui.fangsu.route.short_turn", () -> fangsu$configure(true));
    }

    @Unique
    private void fangsu$addAction(int x, int width, String translationKey, Runnable action) {
        final var screen = (ScreenExtension) (Object) this;
        final int y = screen.getHeightMapped() - 22;
        final var label = ComponentHelper.translatable(translationKey);
        //#if MC_VERSION >= 11903
        final Button button = Button.builder(label, clicked -> action.run()).bounds(x, y, width, 20).build();
        //#else
        //$$ final Button button = new Button(x, y, width, 20, label, clicked -> action.run());
        //#endif
        screen.addChild(new ClickableWidget(button));
    }

    @Unique
    private void fangsu$chooseImportType() {
        final var client = Minecraft.getInstance();
        final var editor = client.screen;
        final var types = java.util.List.of(
                new ConfigChoiceScreen.Choice("express", ComponentHelper.translatable("ui.fangsu.route.import_express").getString()),
                new ConfigChoiceScreen.Choice("short_turn", ComponentHelper.translatable("ui.fangsu.route.import_short_turn").getString()));
        client.setScreen(new ConfigChoiceScreen(editor, "ui.fangsu.route.import", types,
                value -> fangsu$chooseImportParent("express".equals(value))));
    }

    @Unique
    private void fangsu$chooseImportParent(boolean express) {
        final var client = Minecraft.getInstance();
        final var typeScreen = client.screen;
        final var candidates = new java.util.LinkedHashMap<String, Route>();
        final var choices = new ArrayList<ConfigChoiceScreen.Choice>();
        for (final var route : MinecraftClientData.getDashboardInstance().routes) {
            if (route.getId() != fangsu$parentRoute.getId() && route.getTransportMode() == fangsu$parentRoute.getTransportMode()
                    && RouteServices.matchImport(route, fangsu$parentRoute, this::fangsu$stationForPlatform) != null) {
                final String id = Long.toUnsignedString(route.getId());
                candidates.put(id, route);
                choices.add(new ConfigChoiceScreen.Choice(id, route.getName().replace("||", " · ").replace('|', ' ')));
            }
        }
        choices.sort(java.util.Comparator.comparing(ConfigChoiceScreen.Choice::label, String.CASE_INSENSITIVE_ORDER));
        final String title = choices.isEmpty() ? "ui.fangsu.route.import_no_parent" : "ui.fangsu.route.import_parent";
        client.setScreen(new ConfigChoiceScreen(typeScreen, title, choices,
                id -> fangsu$importRoute(candidates.get(id), express)));
    }

    @Unique
    private long fangsu$stationForPlatform(long platformId) {
        final var station = MinecraftClientData.getDashboardInstance().platformIdMap.get(platformId);
        return station == null ? 0 : station.area == null ? 0 : station.area.getId();
    }

    @Unique
    private void fangsu$importRoute(Route source, boolean express) {
        if (source == null || RouteServices.matchImport(source, fangsu$parentRoute, this::fangsu$stationForPlatform) == null) return;
        fangsu$saveParent();
        if (!RouteServices.importService(source, fangsu$parentRoute, express, this::fangsu$stationForPlatform)) return;
        DrawableRoute.clearCache();
        final var data = MinecraftClientData.getDashboardInstance();
        final var request = new org.mtr.core.operation.UpdateDataRequest(data).addRoute(source).addRoute(fangsu$parentRoute);
        org.mtr.mod.InitClient.REGISTRY_CLIENT.sendPacketToServer(new org.mtr.mod.packet.PacketUpdateData(request));
        fangsu$openDashboard(fangsu$parentRoute, false);
    }


    @Unique
    private void fangsu$configure(boolean shortTurn) {
        final String direction = TextUtil.getExtraParts(fangsu$parentRoute.getName());
        final String base = TextUtil.getNonExtraParts(fangsu$parentRoute.getName()) + "||" + direction + "||";
        if (!shortTurn) {
            final String childName = base + direction + "_RAPID";
            for (final var route : MinecraftClientData.getDashboardInstance().routes) {
                final var relation = RouteServices.relation(route);
                if (relation != null && relation.express() && relation.parentRoute() == fangsu$parentRoute.getId()) {
                    fangsu$open(route, false);
                    return;
                }
            }
            fangsu$open(fangsu$copy(fangsu$parentRoute, childName, 0, fangsu$parentRoute.getRoutePlatforms().size() - 1, true), true);
            return;
        }

        fangsu$selectSection(fangsu$parentRoute, null);
    }

    @Unique
    private void fangsu$selectSection(Route source, Route child) {
        final var client = Minecraft.getInstance();
        final var parent = client.screen;
        final var starts = new ArrayList<ConfigChoiceScreen.Choice>();
        for (int index = 0; index < source.getRoutePlatforms().size() - 1; index++) {
            starts.add(new ConfigChoiceScreen.Choice(Integer.toString(index), (index + 1) + ". " + fangsu$stationName(source, index)));
        }
        client.setScreen(new ConfigChoiceScreen(parent, "ui.fangsu.route.first_station", starts, selected -> {
            final int start = Integer.parseInt(selected);
            final var ends = new ArrayList<ConfigChoiceScreen.Choice>();
            for (int index = start + 1; index < source.getRoutePlatforms().size(); index++) {
                ends.add(new ConfigChoiceScreen.Choice(Integer.toString(index), (index + 1) + ". " + fangsu$stationName(source, index)));
            }
            client.setScreen(new ConfigChoiceScreen(parent, "ui.fangsu.route.last_station", ends, endValue -> {
                final int end = Integer.parseInt(endValue);
                if (child == null) {
                    final String direction = TextUtil.getExtraParts(source.getName());
                    final String name = TextUtil.getNonExtraParts(source.getName()) + "||" + direction + "||" + direction + " "
                            + ComponentHelper.translatable("ui.fangsu.route.short_name").getString()
                            + " (" + fangsu$stationName(source, start) + " → " + fangsu$stationName(source, end) + ")";
                    fangsu$open(fangsu$copy(source, name, start, end, false), true);
                } else {
                    fangsu$updateSection(source, child, start, end);
                }
            }));
        }));
    }

    @Unique
    private void fangsu$updateSection(Route source, Route child, int start, int end) {
        fangsu$saveParent();
        final var previous = new java.util.HashMap<java.util.UUID, RoutePlatformData>();
        for (final var stop : child.getRoutePlatforms()) {
            final var metadata = ServiceStop.get(stop);
            if (metadata != null && metadata.parentStop() != null) {
                previous.put(metadata.parentStop(), stop);
            }
        }
        final var selection = fangsu$copy(source, child.getName(), start, end, false);
        child.getRoutePlatforms().clear();
        for (final var stop : selection.getRoutePlatforms()) {
            child.getRoutePlatforms().add(previous.getOrDefault(ServiceStop.get(stop).parentStop(), stop));
        }
        final var data = MinecraftClientData.getDashboardInstance();
        final var request = new org.mtr.core.operation.UpdateDataRequest(data).addRoute(source).addRoute(child);
        org.mtr.mod.InitClient.REGISTRY_CLIENT.sendPacketToServer(new org.mtr.mod.packet.PacketUpdateData(request));
        fangsu$openDashboard(child, false);
    }

    @Unique
    private String fangsu$stationName(Route source, int index) {
        final var platform = source.getRoutePlatforms().get(index).platform;
        return platform == null ? "?" : (platform.area == null ? platform.getName() : platform.area.getName()).replace('|', ' ');
    }

    @Unique
    private Route fangsu$copy(Route source, String name, int start, int end, boolean express) {
        final var data = MinecraftClientData.getDashboardInstance();
        final var route = new Route(source.getTransportMode(), data);
        route.setName(name);
        route.setColor(source.getColor());
        route.setRouteType(source.getRouteType());
        for (int index = start; index <= end; index++) {
            final var stop = source.getRoutePlatforms().get(index);
            final var entry = new RoutePlatformData(((RoutePlatformDataAccess) (Object) stop).fangsu$getPlatformId());
            entry.platform = stop.platform;
            entry.setCustomDestination(stop.getCustomDestination());
            RouteStopStatus.set(entry, RouteStopStatus.of(stop));
            ServiceStop.set(entry, ServiceStop.ensure(stop).child(source.getId(), express));
            route.getRoutePlatforms().add(entry);
        }
        return route;
    }

    @Unique
    private void fangsu$open(Route child, boolean isNew) {
        fangsu$saveParent();
        fangsu$openDashboard(child, isNew);
    }

    @Unique
    private void fangsu$openDashboard(Route child, boolean isNew) {
        final DashboardScreen dashboard = fangsu$dashboard;
        ((RouteEditorAccess) (Object) dashboard).fangsu$editService(child, isNew);
    }

    @org.spongepowered.asm.mixin.gen.Invoker("saveData")
    protected abstract void fangsu$saveParent();
}
