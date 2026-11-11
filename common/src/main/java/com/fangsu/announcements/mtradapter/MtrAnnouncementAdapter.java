package com.fangsu.announcements.mtradapter;

import com.fangsu.train.TrainStatus;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.NTETrainWrapper;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleScriptContext;
import org.mtr.mod.client.MinecraftClientData;
import java.util.*;

/** MTR4 适配：使用实际停站表与现有站序纠正逻辑。 */
public final class MtrAnnouncementAdapter {
    public static List<ApproachingTrain> approaching(Set<Long> platforms) {
        List<ApproachingTrain> result = new ArrayList<>();
        var data = MinecraftClientData.getInstance(); if (data == null) return result;
        for (var vehicle : new ArrayList<>(data.vehicles)) {
            if (vehicle.vehicleExtraData.getSidingId() == 0) continue;
            var wrapper = new NTETrainWrapper(VehicleScriptContext.DataFetchMode.MANDATORY, vehicle);
            if (!wrapper.isOnRoute() || wrapper.getDoorValue() > .01) continue;
            TrainStatus status = new TrainStatus(wrapper);
            var stops = status.getAllPlatforms(); int index = status.getAllPlatformsNextIndex();
            if (index < 0 || index >= stops.size()) continue;
            var stop = stops.get(index); double progress = wrapper.getRailProgress(), remaining = stop.distance - progress;
            if(!Double.isFinite(remaining)||remaining<=.5||stop.platform==null||stop.route==null||!platforms.contains(stop.platform.getId()))continue;
            result.add(new ApproachingTrain(vehicle.getId(),stop.route.getId(),stop.platform.getId(),index,progress,remaining,Double.NaN,wrapper.getDepartureIndex(),stop.destinationName));
        }
        return result;
    }
    public static RidingAnnouncementState riding(){return riding(-1);}
    public static List<RidingAnnouncementState> forecast(){
        List<RidingAnnouncementState> result=new ArrayList<>();for(int n=0;n<2;n++){var state=riding(n);if(state!=null)result.add(state);}return result;
    }
    private static RidingAnnouncementState riding(int forecast) {
        var data = MinecraftClientData.getInstance(); if (data == null) return null;
        for (var vehicle : new ArrayList<>(data.vehicles)) {
            if (!org.mtr.mod.client.VehicleRidingMovement.isRiding(vehicle.getId())) continue;
            var wrapper = new NTETrainWrapper(VehicleScriptContext.DataFetchMode.MANDATORY, vehicle);
            int ridingCar = org.mtr.mod.client.VehicleRidingMovement.getRidingVehicleCarNumberAndOffset(vehicle.getId()).leftInt();
            if (ridingCar < 0 || ridingCar >= vehicle.vehicleExtraData.immutableVehicleCars.size()) return null;
            String vehicleType = vehicle.vehicleExtraData.immutableVehicleCars.get(ridingCar).getVehicleId();
            TrainStatus status = new TrainStatus(wrapper); status.updateRoute();
            var stops = status.getAllPlatforms(); int index = status.getAllPlatformsNextIndex();
            if (wrapper.getDoorValue() > .01) for (int n = 0; n < stops.size(); n++)
                if (stops.get(n).platform != null && stops.get(n).platform.getId() == vehicle.vehicleExtraData.getThisPlatformId()) { index = n; break; }
            if(forecast>=0)index+=(wrapper.getDoorValue()>.01?1:0)+forecast;
            if (index < 0 || index >= stops.size()) return null;
            var stop = stops.get(index); if (stop.platform == null || stop.route == null) return null;
            var routeStops = wrapper.getThisRouteStops(); int localIndex = status.getThisRoutePlatformsNextIndex();
            String destinationName = routeStops.isEmpty() ? stop.destinationName : routeStops.get(routeStops.size() - 1).station == null
                    ? routeStops.get(routeStops.size() - 1).destinationName : routeStops.get(routeStops.size() - 1).station.getName();
            var namedStation = new com.fangsu.announcements.recordings.RecordingFamily.Named(stop.station == null ? 0 : stop.station.getId(), stop.station == null ? "" : stop.station.getName());
            long destinationId = routeStops.isEmpty() || routeStops.get(routeStops.size() - 1).station == null ? 0 : routeStops.get(routeStops.size() - 1).station.getId();
            boolean terminal=!routeStops.isEmpty()&&routeStops.get(routeStops.size()-1).platform!=null&&stop.platform.getId()==routeStops.get(routeStops.size()-1).platform.getId();
            if(forecast>=0&&!stop.route.getPlatforms().isEmpty()){
                var futurePlatforms=stop.route.getPlatforms();var last=futurePlatforms.get(futurePlatforms.size()-1);
                destinationId=last.getStationId();destinationName=last.getStationName();
                localIndex=stop.route.getPlatformIndex(stop.platform.getId());if(localIndex<0)return null;terminal=last.getPlatformId()==stop.platform.getId();
            }
            var destination = new com.fangsu.announcements.recordings.RecordingFamily.Named(destinationId, destinationName == null ? "" : destinationName);
            List<com.fangsu.announcements.recordings.RecordingFamily.Named> transfers = new ArrayList<>();
            if (stop.routeInterchanges != null) for (var route : stop.routeInterchanges) if (!route.name.equals(stop.route.getName()))
                transfers.add(new com.fangsu.announcements.recordings.RecordingFamily.Named(0, route.name));
            transfers = com.fangsu.announcements.recordings.RecordingFamily.filterTransfers(transfers, namedStation.name(), String.valueOf(vehicle.vehicleExtraData.getPreviousRouteNumber()));
            boolean left = false, right = false;
            for (int car = 0; car < status.doorLeftOpen.length; car++) {
                var side = status.getDoorOpenSide(car);
                left |= side == com.fangsu.train.DoorOpenRecord.DoorOpenSide.LEFT || side == com.fangsu.train.DoorOpenRecord.DoorOpenSide.BOTH;
                right |= side == com.fangsu.train.DoorOpenRecord.DoorOpenSide.RIGHT || side == com.fangsu.train.DoorOpenRecord.DoorOpenSide.BOTH;
            }
            if(forecast>0||forecast>=0&&wrapper.getDoorValue()>.01){left=false;right=false;}
            var context = new com.fangsu.announcements.recordings.RecordingFamily.Context(namedStation, destination,
                    new com.fangsu.announcements.recordings.RecordingFamily.Named(stop.route.getId(), stop.route.getName()), List.copyOf(transfers), localIndex <= 1,
                    terminal, left, right);
            double progress = wrapper.getRailProgress();
            return new RidingAnnouncementState(vehicle.getId(), vehicleType, stop.route.getId(), stop.platform.getId(), index, progress, stop.distance-progress, wrapper.getDoorValue()>.01, wrapper.isOnRoute(), context);
        }
        return null;
    }
}
