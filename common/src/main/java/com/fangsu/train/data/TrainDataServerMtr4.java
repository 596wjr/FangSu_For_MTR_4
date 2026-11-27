package com.fangsu.train.data;

import com.fangsu.mixin.InitAccessorMixin;
import com.fangsu.mixin.MainAccessorMixin;
import com.fangsu.mixin.PlatformDoorSidingVehiclesAccessor;
import com.fangsu.mixin.VehicleDoorPredictionAccessor;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;
import org.mtr.mod.Init;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class TrainDataServerMtr4 {
    private record Previous(double progress, boolean reversed, List<PathData> path) {}
    private record Movement(long id, String consist, List<TrainDataRuntime.Slice> track, double speed) {}
    // MTR4 广播器检测车头所在方块及三轴相邻一格。
    private static final TrainDataRuntime.SensorRange SENSOR_RANGE = new TrainDataRuntime.SensorRange(1, 1, 1);

    /** 里程表仅在模拟器任务中读写，主线程持有对应的任务入口。 */
    private static final class Sampler {
        Map<Long, Previous> previous = new HashMap<>();
        long pending;
    }

    private static final Map<ServerLevel, Sampler> SAMPLERS = new HashMap<>();

    private TrainDataServerMtr4() {}

    public static void register() {
        LifecycleEvent.SERVER_STOPPED.register(server -> SAMPLERS.keySet().removeIf(level -> level.getServer() == server));
        TickEvent.SERVER_POST.register(TrainDataServerMtr4::tick);
    }

    private static void tick(MinecraftServer server) {
        var main = InitAccessorMixin.getMain();
        if (main == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            String world = Init.getWorldId(new org.mtr.mapping.holder.World(level));
            for (Simulator simulator : ((MainAccessorMixin) main).getSimulators()) {
                if (!Objects.equals(simulator.dimension, world)) continue;
                Sampler sampler = SAMPLERS.computeIfAbsent(level, ignored -> new Sampler());
                if (sampler.pending != 0) break;
                long stamp = System.nanoTime();
                sampler.pending = stamp;
                simulator.run(() -> {
                    List<Movement> movements = new ArrayList<>();
                    try {
                        Map<Long, Previous> next = new HashMap<>();
                        for (var siding : simulator.sidings) {
                            for (Vehicle vehicle : ((PlatformDoorSidingVehiclesAccessor) (Object) siding).fangsu$vehicles()) {
                                Movement movement = sample(simulator, vehicle, sampler.previous.get(vehicle.getId()), next);
                                if (movement != null) movements.add(movement);
                            }
                        }
                        sampler.previous = next;
                    } finally {
                        server.execute(() -> {
                            if (SAMPLERS.get(level) != sampler || sampler.pending != stamp) return;
                            sampler.pending = 0;
                            movements.forEach(movement -> TrainDataRuntime.move(level, movement.id(), movement.consist(), movement.track(),
                                    movement.speed(), SENSOR_RANGE));
                        });
                    }
                });
                break;
            }
        }
    }

    private static Movement sample(Simulator simulator, Vehicle vehicle, Previous old, Map<Long, Previous> next) {
        List<PathData> path = vehicle.vehicleExtraData.immutablePath;
        if (vehicle.getTransportMode() != org.mtr.core.data.TransportMode.TRAIN || path.isEmpty()) return null;
        VehicleDoorPredictionAccessor state = (VehicleDoorPredictionAccessor) vehicle;
        double head = state.fangsu$getRailProgress();
        double current = Math.max(path.get(0).getStartDistance(), Math.min(path.get(path.size() - 1).getEndDistance(), head));
        boolean reversed = vehicle.getReversed();
        long id = vehicle.getId();
        double previous = old == null ? current : TrainRailTravel.start(old.progress(), current,
                reversed != old.reversed() || old.path() != path);
        next.put(id, new Previous(current, reversed, path));
        long route = vehicle.vehicleExtraData.getThisRouteId();
        List<TrainDataRuntime.Slice> slices = new ArrayList<>();
        int low = 0;
        int high = path.size() - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (path.get(middle).getEndDistance() < previous) low = middle + 1;
            else high = middle;
        }
        for (int index = low; index < path.size(); index++) {
            PathData part = path.get(index);
            double begin = part.getStartDistance();
            double end = part.getEndDistance();
            if (begin > current) break;
            var platform = simulator.platformIdMap.get(part.getSavedRailBaseId());
            long station = platform == null || platform.area == null ? 0 : platform.area.getId();
            boolean opposite = part.getHexId(false).compareTo(part.getHexId(true)) > 0;
            TrainRailTravel.append(slices, distance -> {
                var vector = part.getPosition(distance - begin);
                return new Vec3(vector.x, vector.y, vector.z);
            }, Math.max(begin, previous), Math.min(end, current), route, station, opposite);
            if (end > current) break;
        }
        String consist = vehicle.vehicleExtraData.immutableVehicleCars.stream().map(car -> car.getVehicleId())
                .collect(Collectors.joining("\u001f"));
        return new Movement(id, consist, slices, state.fangsu$getSpeed());
    }
}
