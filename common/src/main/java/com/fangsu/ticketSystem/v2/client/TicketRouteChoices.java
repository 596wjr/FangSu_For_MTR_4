package com.fangsu.ticketSystem.v2.client;
import java.util.*;
/** 只用客户端线路组织原界面，车站可售范围及价格始终来自服务端。 */
public final class TicketRouteChoices {
    public record RouteChoice(String name,int color,Set<Long> stations) { }
    public static List<RouteChoice> load(){
        List<RouteChoice> result=new ArrayList<>();
        var data=org.mtr.mod.client.MinecraftClientData.getInstance();
        if(!data.simplifiedRoutes.isEmpty())for(var route:data.simplifiedRoutes){
            Set<Long> stations=new LinkedHashSet<>();for(var platform:route.getPlatforms())stations.add(platform.getStationId());
            result.add(new RouteChoice(com.fangsu.scripting.TextUtil.getNonExtraParts(route.getName()),route.getColor(),stations));
        }else for(var route:data.routes){
            Set<Long> stations=new LinkedHashSet<>();for(var platform:route.getRoutePlatforms()){
                var p=platform.getPlatform();if(p!=null&&p.area!=null)stations.add(p.area.getId());
            }
            result.add(new RouteChoice(com.fangsu.scripting.TextUtil.getNonExtraParts(route.getName()),route.getColor(),stations));
        }
        return result;
    }
    private TicketRouteChoices(){ }
}
