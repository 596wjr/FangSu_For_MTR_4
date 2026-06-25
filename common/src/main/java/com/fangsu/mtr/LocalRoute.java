package com.fangsu.mtr;

import com.fangsu.scripting.TextUtil;
import com.fangsu.utils.MtrUtil;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.SimplifiedRoutePlatform;
import org.mtr.core.data.Station;

import java.util.*;

public class LocalRoute {
    public RouteType routeType;
    public boolean isLightRailRoute;
    public boolean isHidden;
    public boolean disableNextStationAnnouncements;
    public CircularState circularState;
    public String lightRailRouteNumber;
    public final List<RoutePlatform> platformIds;
    public String name;
    public int color;
    public long id;

    private LocalRouteDetail cachedRouteDetail;

    public LocalRoute(Route route) {
        this.routeType = RouteType.valueOf(route.getRouteType().name());
        this.isLightRailRoute = route.getRouteType() == org.mtr.core.data.RouteType.LIGHT_RAIL;
        this.isHidden = route.getHidden();
        this.disableNextStationAnnouncements = false;
        this.circularState = CircularState.valueOf(route.getCircularState().name());
        this.lightRailRouteNumber = route.getRouteNumber();
        this.platformIds = new ArrayList<>();
        this.name = route.getName();
        this.id = route.getId();
        this.color = route.getColor();
        for (final RoutePlatformData p : route.getRoutePlatforms()) {
            this.platformIds.add(new RoutePlatform(p.platform != null ? p.platform.getId() : 0L, p.getCustomDestination()));
        }
    }

    /**
     * 从 {@link SimplifiedRoute} 构造（客户端远程路线无完整 Route 数据时的回退）。
     */
    public LocalRoute(SimplifiedRoute route) {
        this.routeType = RouteType.NORMAL;
        this.isLightRailRoute = false;
        this.isHidden = false;
        this.disableNextStationAnnouncements = false;
        this.circularState = CircularState.valueOf(route.getCircularState().name());
        this.lightRailRouteNumber = "";
        this.platformIds = new ArrayList<>();
        this.name = route.getName();
        this.id = route.getId();
        this.color = route.getColor();
        for (final SimplifiedRoutePlatform p : route.getPlatforms()) {
            this.platformIds.add(new RoutePlatform(p.getPlatformId(), p.getDestination()));
        }
    }

    public LocalRoute() {
        this.routeType = RouteType.NORMAL;
        this.isLightRailRoute = false;
        this.isHidden = false;
        this.disableNextStationAnnouncements = false;
        this.circularState = CircularState.NONE;
        this.lightRailRouteNumber = "";
        this.name = "?";
        this.id = 0L;
        this.color = 123456;
        this.platformIds = new ArrayList<>();
    }

    public static class RoutePlatform {
        public String customDestination;
        public final long platformId;

        public RoutePlatform(long platformId, String customDestination) {
            this.platformId = platformId;
            this.customDestination = customDestination;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof RoutePlatform o) {
                return platformId == o.platformId && customDestination.equals(o.customDestination);
            }
            return false;
        }
    }

    public int getPlatformIdIndex(long platformId) {
        for (int i = 0; i < platformIds.size(); i++) {
            if (platformIds.get(i).platformId == platformId) {
                return i;
            }
        }
        return -1;
    }

    public enum CircularState {
        NONE,
        CLOCKWISE,
        ANTICLOCKWISE;
    }

    public enum RouteType {
        NORMAL,
        LIGHT_RAIL,
        HIGH_SPEED;
    }

    public LocalRouteDetail asRouteDetail() {
        if (cachedRouteDetail != null) return cachedRouteDetail;
        final List<LocalRouteDetail.StationDetails> stations = new ArrayList<>();

        // 优先使用 SimplifiedRoute（客户端数据始终完整）
        final SimplifiedRoute sr = MtrUtil.getSimplifiedRouteById(this.id);
        if (sr != null && !sr.getPlatforms().isEmpty()) {
            for (final SimplifiedRoutePlatform sp : sr.getPlatforms()) {
                final String stationName = sp.getStationName();
                if (stationName == null || stationName.isEmpty()) {
                    stations.add(new LocalRouteDetail.StationDetails("未命名|Undefined", null));
                    continue;
                }

                // 获取换乘信息：通过站台查找该站所有路线
                final Set<ColorNameTuple> trans = new HashSet<>();
                final Platform plat = MtrUtil.getPlatformById(sp.getPlatformId());
                if (plat != null && plat.area != null) {
                    for (final Platform plat1 : plat.area.savedRails) {
                        for (final Route route : plat1.routes) {
                            if (!route.getHidden() &&
                                    !(TextUtil.getCjkMatching(this.name, true).equals(TextUtil.getCjkMatching(route.getName(), true)) &&
                                            TextUtil.getCjkMatching(this.name, false).equals(TextUtil.getCjkMatching(route.getName(), false)))
                            ) {
                                trans.add(new ColorNameTuple(route.getColor(), TextUtil.getNonExtraParts(route.getName())));
                            }
                        }
                    }
                } else {
                    // 远端车站：从 SimplifiedRoute 查找经过该站的路线
                    final long stationId = sp.getStationId();
                    for (final SimplifiedRoute otherSr : MtrUtil.getSimplifiedRoutes()) {
                        if (otherSr.getId() != this.id) {
                            for (final SimplifiedRoutePlatform osp : otherSr.getPlatforms()) {
                                if (osp.getStationId() == stationId) {
                                    trans.add(new ColorNameTuple(otherSr.getColor(), TextUtil.getNonExtraParts(otherSr.getName())));
                                    break;
                                }
                            }
                        }
                    }
                }

                if (!trans.isEmpty()) {
                    stations.add(new LocalRouteDetail.StationDetails(stationName, new ArrayList<>(trans)));
                } else {
                    stations.add(new LocalRouteDetail.StationDetails(stationName, List.of()));
                }
            }
        } else {
            // 回退：原有逻辑（通过 Platform/Station 查找）
            for (final RoutePlatform p : platformIds) {
                final long platformId = p.platformId;
                final Platform plat = MtrUtil.getPlatformById(platformId);
                if (plat != null) {
                    final Station stn = plat.area;
                    if (stn != null) {
                        final Set<ColorNameTuple> trans = new HashSet<>();
                        for (final Platform plat1 : stn.savedRails) {
                            for (final Route route : plat1.routes) {
                                if (!route.getHidden() &&
                                        !(TextUtil.getCjkMatching(this.name, true).equals(TextUtil.getCjkMatching(route.getName(), true)) &&
                                                TextUtil.getCjkMatching(this.name, false).equals(TextUtil.getCjkMatching(route.getName(), false)))
                                ) {
                                    trans.add(new ColorNameTuple(route.getColor(), TextUtil.getNonExtraParts(route.getName())));
                                }
                            }
                        }
                        if (!trans.isEmpty()) {
                            stations.add(new LocalRouteDetail.StationDetails(stn.getName(), new ArrayList<>(trans)));
                        } else {
                            stations.add(new LocalRouteDetail.StationDetails(stn.getName(), List.of()));
                        }
                    } else {
                        stations.add(new LocalRouteDetail.StationDetails("未命名|Undefined", null));
                    }
                }
            }
        }

        final LocalRouteDetail result = new LocalRouteDetail(name, color, circularState, 0, stations);
        cachedRouteDetail = result;
        return result;
    }
}
