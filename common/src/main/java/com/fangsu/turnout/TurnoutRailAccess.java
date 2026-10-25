package com.fangsu.turnout;

import com.fangsu.Main;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TwoPositionsBase;
import org.mtr.core.operation.RailsRequest;
import org.mtr.core.operation.RailsResponse;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.mod.Init;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mapping.holder.World;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** MTR4 的服务端通过 TSC RailsRequest 回查权威数据，绝不借用 MinecraftClientData 放行。 */
public final class TurnoutRailAccess {
    public static final int MAX_LINKS=512;
    private TurnoutRailAccess() { }
    public static TurnoutRailGraph client(Level level,BlockPos center) {
        TurnoutRailGraph graph=new TurnoutRailGraph();if(level==null||!level.isClientSide)return graph;
        try {
            var data=MinecraftClientData.getInstance();Set<TurnoutRailGraph.Link> seen=new HashSet<>();
            synchronized(data.positionsToRail) {
                for(var row:data.positionsToRail.entrySet())for(var entry:row.getValue().entrySet()) {
                    BlockPos a=block(row.getKey()),b=block(entry.getKey());
                    if(a.distSqr(center)>4096||b.distSqr(center)>4096||!seen.add(new TurnoutRailGraph.Link(a,b)))continue;
                    TurnoutRailGraph.Edge edge=sample(a,b,entry.getValue());if(edge!=null)graph.add(edge);
                }
            }
        }catch(RuntimeException e){Main.LOGGER.debug("[turnout] 客户端轨道同步中",e);}
        return graph;
    }
    public static void server(Level level,BlockPos center,List<TurnoutRailGraph.Link> candidates,Consumer<TurnoutRailGraph> result) {
        if(level==null||level.isClientSide||level.getServer()==null||candidates.size()>MAX_LINKS){result.accept(new TurnoutRailGraph());return;}
        RailsRequest request=new RailsRequest();Map<String,TurnoutRailGraph.Link> indexed=new HashMap<>();
        for(TurnoutRailGraph.Link link:candidates) {
            if(link.a().distSqr(center)>4096||link.b().distSqr(center)>4096)continue;
            String id=TwoPositionsBase.getHexId(position(link.a()),position(link.b()));indexed.put(id,link);request.addRailId(id);
        }
        if(indexed.isEmpty()){result.accept(new TurnoutRailGraph());return;}
        CompletableFuture<TurnoutRailGraph> future=new CompletableFuture<>();
        future.orTimeout(5,TimeUnit.SECONDS).whenComplete((graph,error)->level.getServer().execute(()->result.accept(error==null?graph:new TurnoutRailGraph())));
        try {
            World world=new World(level);
            Init.sendMessageC2S(OperationProcessor.RAILS,world.getServer(),world,request,response->{
                TurnoutRailGraph graph=new TurnoutRailGraph();
                for(Rail rail:response.getRails()) {
                    TurnoutRailGraph.Link link=indexed.get(rail.getHexId());if(link==null)continue;
                    TurnoutRailGraph.Edge edge=sample(link.a(),link.b(),rail);if(edge!=null)graph.add(edge);
                }
                future.complete(graph);
            },RailsResponse.class);
        }catch(RuntimeException e){Main.LOGGER.warn("[turnout] TSC 轨道查询失败",e);future.complete(new TurnoutRailGraph());}
    }
    private static Position position(BlockPos p){return new Position(p.getX(),p.getY(),p.getZ());}
    private static BlockPos block(Position p){return new BlockPos((int)p.getX(),(int)p.getY(),(int)p.getZ());}
    private static TurnoutRailGraph.Edge sample(BlockPos a,BlockPos b,Rail rail) {
        if(rail==null||rail.railMath==null)return null;
        try {
            double length=rail.railMath.getLength();if(!Double.isFinite(length)||length<=1e-6D)return null;
            List<Vec3> points=new ArrayList<>();int count=Math.max(2,Math.min(256,(int)Math.ceil(length/0.5D)));
            for(int i=0;i<=count;i++){var p=rail.railMath.getPosition(length*i/count,false);if(p==null||!Double.isFinite(p.x())||!Double.isFinite(p.y())||!Double.isFinite(p.z()))return null;points.add(new Vec3(p.x(),p.y(),p.z()));}
            if(points.get(0).distanceToSqr(Vec3.atCenterOf(a))>points.get(points.size()-1).distanceToSqr(Vec3.atCenterOf(a)))Collections.reverse(points);
            return new TurnoutRailGraph.Edge(a,b,rail.isValid(),points);
        }catch(RuntimeException e){return null;}
    }
}
