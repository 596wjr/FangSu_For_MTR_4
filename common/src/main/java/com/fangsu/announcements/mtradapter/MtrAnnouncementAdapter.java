package com.fangsu.announcements.mtradapter;

import com.fangsu.mtr.RouteStopStatus;
import com.fangsu.train.JcmStopLookup;
import com.fangsu.train.TrainStatus;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.NTETrainWrapper;
import com.lx862.mtrscripting.mod.impl.mtr.vehicle.VehicleScriptContext;
import org.mtr.core.data.PathData;
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
            if (!JcmStopLookup.matchesPhysicalStop(stop, JcmStopLookup.nextPhysicalStop(vehicle.vehicleExtraData, progress))) continue;
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
            if (index < 0 || index >= stops.size()) return null;
            final double progress = wrapper.getRailProgress();
            final boolean doorsOpen = wrapper.getDoorValue() > .01;
            final PathData physical = JcmStopLookup.nextPhysicalStop(vehicle.vehicleExtraData, progress);
            if (!JcmStopLookup.matchesPhysicalStop(stops.get(index), physical)) return null;
            final int currentIndex = index;
            if (forecast >= 0) index += (doorsOpen ? 1 : 0) + forecast;
            if (index >= stops.size()) return null;
            var stop = stops.get(index); if (stop.platform == null || stop.route == null) return null;
            // 共用站台可以同时是上一趟终点和本趟首站，使用与 LCD 相同的当前方向。
            if (index == currentIndex && status.currentRoute != null && stop.roundUpRoute != null
                    && stop.roundUpRoute.route != null && stop.roundUpRoute.route.getId() == status.currentRoute.id) {
                stop = stop.roundUpRoute;
            }
            var routeStops = wrapper.getThisRouteStops();
            int localIndex = routeStops.indexOf(stop);
            String destinationName = routeStops.isEmpty() ? stop.destinationName : routeStops.get(routeStops.size() - 1).station == null
                    ? routeStops.get(routeStops.size() - 1).destinationName : routeStops.get(routeStops.size() - 1).station.getName();
            var namedStation = new com.fangsu.announcements.recordings.RecordingFamily.Named(stop.station == null ? 0 : stop.station.getId(), stop.station == null ? "" : stop.station.getName());
            long destinationId = routeStops.isEmpty() || routeStops.get(routeStops.size() - 1).station == null ? 0 : routeStops.get(routeStops.size() - 1).station.getId();
            boolean terminal = localIndex >= 0 && localIndex == routeStops.size() - 1;
            if (localIndex < 0) {
                final var passengerPlatforms = stop.route.getPlatforms().stream()
                        .filter(platform -> RouteStopStatus.of(platform) == RouteStopStatus.NORMAL).toList();
                final long platformId = stop.platform.getId();
                for (int n = 0; n < passengerPlatforms.size(); n++) {
                    if (passengerPlatforms.get(n).getPlatformId() == platformId) {
                        localIndex = n;
                        break;
                    }
                }
                if (localIndex < 0) return null;
                final var last = passengerPlatforms.get(passengerPlatforms.size() - 1);
                destinationId = last.getStationId();
                destinationName = last.getStationName();
                terminal = localIndex == passengerPlatforms.size() - 1;
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
            final double stopDistance = index == currentIndex && physical != null ? physical.getEndDistance() : stop.distance;
            return new RidingAnnouncementState(vehicle.getId(), vehicleType, stop.route.getId(), stop.platform.getId(), index, progress,
                    stopDistance - progress, doorsOpen, wrapper.isOnRoute(), context);
        }
        return null;
    }
}
