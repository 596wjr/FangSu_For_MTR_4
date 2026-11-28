package com.fangsu.train.selection;

import org.mtr.core.data.Siding;
import org.mtr.core.data.VehicleCar;

import java.util.ArrayList;
import java.util.List;

public final class TrainFormation {
    private final Siding siding;

    public TrainFormation(Siding siding) {
        this.siding = siding;
    }

    public boolean fits(List<VehicleCar> cars) {
        if (cars.size() > siding.getTransportMode().maxLength) return false;
        double occupied = 0;
        for (int i = 0; i < cars.size(); i++) {
            VehicleCar car = cars.get(i);
            if (!fitsNext(occupied, car, i == 0)) return false;
            occupied += car.getTotalLength(i == 0, false);
        }
        return true;
    }

    public double usedLength(List<VehicleCar> cars) {
        double length = 0;
        for (int i = 0; i < cars.size(); i++) length += cars.get(i).getTotalLength(i == 0, i == cars.size() - 1);
        return length;
    }

    public List<VehicleCar> duplicate(List<VehicleCar> cars) {
        if (cars.isEmpty() || !fits(cars)) return cars;
        List<VehicleCar> expanded = new ArrayList<>(cars);
        double occupied = 0;
        for (int i = 0; i < cars.size(); i++) occupied += cars.get(i).getTotalLength(i == 0, false);
        double cycleStart = occupied;
        for (int i = 0; expanded.size() < siding.getTransportMode().maxLength; i = (i + 1) % cars.size()) {
            VehicleCar next = cars.get(i);
            if (!fitsNext(occupied, next, false)) break;
            expanded.add(next);
            occupied += next.getTotalLength(false, false);
            if (i == cars.size() - 1) {
                if (occupied <= cycleStart) break;
                cycleStart = occupied;
            }
        }
        return expanded;
    }

    public Result build(TrainCatalog.Variant variant, TrainCatalog catalog) {
        List<TrainFormationPresets.Item> rules = TrainFormationPresets.get(variant.prefix());
        return rules == null ? buildDefault(variant) : buildPreset(rules, catalog);
    }

    private Result buildDefault(TrainCatalog.Variant variant) {
        VehicleCar head = carOfType(variant, "cab_1");
        VehicleCar trailer = carOfType(variant, "trailer");
        VehicleCar tail = carOfType(variant, "cab_2");
        if (head == null || trailer == null || tail == null) return Result.error("no_standard_formation", variant.prefix());
        if (!fits(List.of(head, tail))) return Result.error("does_not_fit", "");

        List<VehicleCar> cars = new ArrayList<>();
        cars.add(head);
        double occupied = head.getTotalLength(true, false);
        double trailerLength = trailer.getTotalLength(false, false);
        if (Double.isFinite(trailerLength) && trailerLength > 0) {
            while (cars.size() < siding.getTransportMode().maxLength - 1 && fitsNext(occupied, trailer, false)) {
                double nextOccupied = occupied + trailerLength;
                if (nextOccupied <= occupied || !fitsNext(nextOccupied, tail, false)) break;
                cars.add(trailer);
                occupied = nextOccupied;
            }
        }
        cars.add(tail);
        return Result.success(cars);
    }

    private static VehicleCar carOfType(TrainCatalog.Variant variant, String type) {
        return variant.allCars().stream().filter(car -> car.role().type().equals(type))
                .findFirst().map(TrainCatalog.Car::createCar).orElse(null);
    }

    private Result buildPreset(List<TrainFormationPresets.Item> rules, TrainCatalog catalog) {
        List<Choice> choices = new ArrayList<>();
        for (TrainFormationPresets.Item rule : rules) {
            TrainCatalog.Car resource = catalog.find(rule.id());
            if (resource == null) return Result.error("missing_car", rule.id());
            VehicleCar car = resource.createCar();
            if (!Double.isFinite(car.getTotalLength(false, false)) || car.getTotalLength(false, false) <= 0) {
                return Result.error("invalid_car_length", rule.id());
            }
            choices.add(new Choice(car, rule.filter()));
        }
        for (int count = maxPresetLength(choices); count > 0; count--) {
            List<VehicleCar> cars = new ArrayList<>();
            double occupied = 0;
            for (int index = 0; index < count; index++) {
                VehicleCar car = firstMatch(choices, index, count);
                if (car == null || !fitsNext(occupied, car, index == 0)) break;
                cars.add(car);
                occupied += car.getTotalLength(index == 0, false);
            }
            if (cars.size() == count) return Result.success(cars);
        }
        return Result.error("no_preset_fit", "");
    }

    private int maxPresetLength(List<Choice> choices) {
        double first = Double.POSITIVE_INFINITY;
        double middle = Double.POSITIVE_INFINITY;
        double last = Double.POSITIVE_INFINITY;
        for (Choice choice : choices) {
            first = Math.min(first, choice.car.getTotalLength(true, false));
            middle = Math.min(middle, choice.car.getTotalLength(false, false));
            last = Math.min(last, choice.car.getTotalLength(false, true));
        }
        double available = siding.getRailLength() - first - last;
        if (!Double.isFinite(available) || available < 0) return 1;
        // 各车位最短长度给出上界，向上取整避免误跳过受浮点舍入影响的候选。
        return (int) Math.min(siding.getTransportMode().maxLength, 2 + Math.ceil(available / middle));
    }

    private static VehicleCar firstMatch(List<Choice> choices, int index, int count) {
        for (Choice choice : choices) if (choice.filter.matches(index, count)) return choice.car;
        return null;
    }

    private boolean fitsNext(double occupied, VehicleCar car, boolean first) {
        double length = occupied + car.getTotalLength(first, true);
        return Double.isFinite(length) && length <= siding.getRailLength();
    }

    private record Choice(VehicleCar car, CarPositionFilter filter) {}

    public record Result(List<VehicleCar> cars, String error, String argument) {
        private static Result success(List<VehicleCar> cars) { return new Result(cars, null, ""); }
        private static Result error(String key, String argument) { return new Result(List.of(), key, argument); }
    }
}
