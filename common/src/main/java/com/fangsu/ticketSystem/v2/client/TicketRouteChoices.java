package com.fangsu.ticketSystem.v2.client;
import java.util.*;
/** 只用客户端线路组织原界面，车站可售范围及价格始终来自服务端。 */
public final class TicketRouteChoices {
    public record RouteChoice(String name,int color,Set<Long> stations) { }
    public static List<RouteChoice> load(){
        Map<String, RouteChoice> grouped = new LinkedHashMap<>();
        var data=org.mtr.mod.client.MinecraftClientData.getInstance();
        if(!data.simplifiedRoutes.isEmpty())for(var route:data.simplifiedRoutes){
            Set<Long> stations=new LinkedHashSet<>();for(var platform:route.getPlatforms())stations.add(platform.getStationId());
            merge(grouped, route.getName(), route.getColor(), stations);
        }else for(var route:data.routes){
            Set<Long> stations=new LinkedHashSet<>();for(var platform:route.getRoutePlatforms()){
                var p=platform.getPlatform();if(p!=null&&p.area!=null)stations.add(p.area.getId());
            }
            merge(grouped, route.getName(), route.getColor(), stations);
        }
        return grouped.values().stream()
                .sorted(Comparator.comparing(RouteChoice::name).thenComparingInt(RouteChoice::color)).toList();
    }

    private static void merge(Map<String, RouteChoice> grouped, String name, int color, Set<Long> stations) {
        String key = com.fangsu.scripting.TextUtil.getNonExtraParts(name);
        if (key.isBlank()) return;
        grouped.computeIfAbsent(key, ignored -> new RouteChoice(key, color, new LinkedHashSet<>()))
                .stations().addAll(stations);
    }
    private TicketRouteChoices(){ }
}
