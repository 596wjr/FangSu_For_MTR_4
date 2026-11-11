package com.fangsu.platform;

import com.fangsu.blockEntities.BlockEntityAutoPlatformComponent;
import com.fangsu.mixin.*;
import dev.architectury.event.events.common.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.mtr.mod.Init;
import java.util.*;

/** MTR4 的门目标来自模拟线程，绝不以客户端门开度上报决定服务端碰撞。 */
public final class PlatformDoorTrainDriver {
    private record Frame(Set<String> rails,double x,double y,double z,double ux,double uz,double length,double width) { }
    private record Snapshot(long time,List<Frame> frames) { }
    private static final Map<ServerLevel,Set<BlockPos>> HOSTS=new HashMap<>();
    private static final Map<ServerLevel,Snapshot> SNAPSHOTS=new HashMap<>();
    private static final Map<ServerLevel,Long> PENDING=new HashMap<>();
    private static boolean installed,logged;private static int tick;
    public static void observe(ServerLevel level,BlockPos pos){HOSTS.computeIfAbsent(level,k->new HashSet<>()).add(pos.immutable());}
    public static void register(){if(installed)return;installed=true;
        LifecycleEvent.SERVER_STOPPED.register(server->{HOSTS.keySet().removeIf(w->w.getServer()==server);SNAPSHOTS.keySet().removeIf(w->w.getServer()==server);PENDING.keySet().removeIf(w->w.getServer()==server);});
        TickEvent.SERVER_POST.register(server->{if(++tick%5!=0)return;long now=System.nanoTime();
            for(var row:new ArrayList<>(HOSTS.entrySet())){ServerLevel level=row.getKey();if(level.getServer()!=server)continue;
                row.getValue().removeIf(pos->!level.hasChunkAt(pos)||!(level.getBlockEntity(pos) instanceof BlockEntityAutoPlatformComponent));
                if(row.getValue().isEmpty()){HOSTS.remove(level);SNAPSHOTS.remove(level);continue;}
                Snapshot snapshot=SNAPSHOTS.get(level);List<Frame> frames=snapshot!=null&&now-snapshot.time()<2_000_000_000L?snapshot.frames():List.of();
                for(BlockPos pos:row.getValue())if(level.getBlockEntity(pos) instanceof BlockEntityAutoPlatformComponent host){boolean open=false;var metadata=host.metadata();String rail=key(BlockPos.of(metadata.getLong("first")),BlockPos.of(metadata.getLong("last")));
                    for(var frame:frames){if(!frame.rails().contains(rail))continue;for(var part:host.components()){
                        double dx=pos.getX()+.5+part.dx()-frame.x(),dz=pos.getZ()+.5+part.dz()-frame.z();
                        if(Math.abs(dx*frame.ux()+dz*frame.uz())<=frame.length()/2+.75&&Math.abs(-dx*frame.uz()+dz*frame.ux())<=frame.width()/2+1.25
                                &&Math.abs(pos.getY()+part.dy()-frame.y())<6&&Math.abs(Math.cos(part.yaw())*frame.ux()-Math.sin(part.yaw())*frame.uz())>.5){open=true;break;}
                    }if(open)break;}host.setDoorTarget(open);host.setDoorValue(0);}
                if(!PENDING.containsKey(level)||now-PENDING.get(level)>2_000_000_000L)request(level,now);
            }
        });
    }
    private static void request(ServerLevel level,long stamp){
        String dimension=Init.getWorldId(new org.mtr.mapping.holder.World(level));
        for(var simulator:((MainAccessorMixin)InitAccessorMixin.getMain()).getSimulators())if(simulator.dimension.equals(dimension)){
            PENDING.put(level,stamp);simulator.run(()->{
                List<Frame> frames=new ArrayList<>();
                try{for(var siding:simulator.sidings)for(var vehicle:((PlatformDoorSidingVehiclesAccessor)(Object)siding).fangsu$vehicles()){
                    if(vehicle.vehicleExtraData.getDoorMultiplier()<=0)continue;Set<String> rails=new HashSet<>();double head=((VehicleDoorPredictionAccessor)vehicle).fangsu$getRailProgress(),tail=head-vehicle.vehicleExtraData.getTotalVehicleLength();
                    for(var path:vehicle.vehicleExtraData.immutablePath)if(path.getStartDistance()<=head+.01&&path.getEndDistance()>=tail-.01){var a=path.getOrderedPosition1();var b=path.getOrderedPosition2();rails.add(key(new BlockPos((int)a.getX(),(int)a.getY(),(int)a.getZ()),new BlockPos((int)b.getX(),(int)b.getY(),(int)b.getZ())));}
                    Set<String> bound=Set.copyOf(rails);
                    for(var car:vehicle.getVehicleCarsAndPositions()){
                        var bogies=car.right();if(bogies.isEmpty())continue;var first=bogies.get(0);var last=bogies.get(bogies.size()-1);
                        double ax=(first.left().x+first.right().x)/2,az=(first.left().z+first.right().z)/2,bx=(last.left().x+last.right().x)/2,bz=(last.left().z+last.right().z)/2;
                        double dx=ax-bx,dz=az-bz;if(Math.hypot(dx,dz)<1e-6){dx=first.left().x-first.right().x;dz=first.left().z-first.right().z;}double n=Math.hypot(dx,dz);if(n<1e-6)continue;
                        frames.add(new Frame(bound,(ax+bx)/2,(first.left().y+first.right().y+last.left().y+last.right().y)/4,(az+bz)/2,dx/n,dz/n,car.left().getLength(),car.left().getWidth()));
                    }
                }}catch(RuntimeException e){frames.clear();if(!logged){logged=true;com.fangsu.Main.LOGGER.warn("[FangSu] Automatic platform door vehicle snapshot unavailable ({})",e.getClass().getSimpleName());}}
                List<Frame> ready=List.copyOf(frames);level.getServer().execute(()->{if(Objects.equals(PENDING.get(level),stamp)){PENDING.remove(level);SNAPSHOTS.put(level,new Snapshot(System.nanoTime(),ready));}});
            });return;
        }
    }
    private static String key(BlockPos a,BlockPos b){String x=a.getX()+","+a.getY()+","+a.getZ(),y=b.getX()+","+b.getY()+","+b.getZ();return x.compareTo(y)<=0?x+"|"+y:y+"|"+x;}
    private PlatformDoorTrainDriver(){ }
}
