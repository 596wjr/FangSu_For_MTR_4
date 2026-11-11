package com.fangsu.platform;

import com.fangsu.mixin.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.mtr.core.data.*;
import org.mtr.mod.Init;
import java.util.*;
import java.util.function.Consumer;

/** MTR4 在模拟线程读取与参考模组相同的轨道采样、节点朝向，再交回游戏线程。 */
public final class PlatformRailSampler {
    public static void sample(ServerLevel level,List<BlockPos> nodes,Consumer<PlatformRailSample> complete){
        if(nodes.size()!=2||nodes.get(0).equals(nodes.get(1))){complete.accept(PlatformRailSample.empty());return;}
        String dimension=Init.getWorldId(new org.mtr.mapping.holder.World(level));
        for(var simulator:((MainAccessorMixin)InitAccessorMixin.getMain()).getSimulators())if(simulator.dimension.equals(dimension)){
            simulator.run(()->{
                PlatformRailSample sample;
                try{
                    BlockPos a=nodes.get(0),b=nodes.get(1);Position start=new Position(a.getX(),a.getY(),a.getZ()),end=new Position(b.getX(),b.getY(),b.getZ());
                    var rail=simulator.railIdMap.get(TwoPositionsBase.getHexId(start,end));if(rail==null)throw new IllegalArgumentException();
                    double length=rail.railMath.getLength();if(!Double.isFinite(length)||length<.05||length>2048)throw new IllegalArgumentException();
                    var first=rail.railMath.getPosition(0,false);var last=rail.railMath.getPosition(length,false);boolean reverse=Math.hypot(first.x-a.getX()-.5,first.z-a.getZ()-.5)>Math.hypot(last.x-a.getX()-.5,last.z-a.getZ()-.5);
                    List<CurvedPlatformPlanner.Point> points=new ArrayList<>();int steps=(int)Math.ceil(length*4);
                    for(int i=0;i<=steps;i++){double s=Math.min(i*.25,length);var p=rail.railMath.getPosition(reverse?length-s:s,false);points.add(new CurvedPlatformPlanner.Point(p.x,p.y,p.z));}
                    var sa=rail.getStartAngle(start);var ea=rail.getStartAngle(end);sample=new PlatformRailSample(points,sa.cos,sa.sin,ea.cos,ea.sin);
                }catch(Exception e){sample=PlatformRailSample.empty();}
                PlatformRailSample snapshot=sample;level.getServer().execute(()->complete.accept(snapshot));
            });return;
        }
        complete.accept(PlatformRailSample.empty());
    }
}
