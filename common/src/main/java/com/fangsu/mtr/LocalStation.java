package com.fangsu.mtr;

import org.mtr.core.data.Station;
import org.mtr.core.data.StationExit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LocalStation extends LocalAreaBase {
    public int zone;
    public final Map<String, List<String>> exits;

    private final Station raw;

    public LocalStation(Station station) {
        super(
                station.getId(),
                station.getName(),
                station.getColor(),
                (int) station.getMinX(),
                (int) station.getMaxX(),
                (int) station.getMinZ(),
                (int) station.getMaxZ()
        );
        this.raw = station;
        this.zone = (int) station.getZone1();
        this.exits = new HashMap<>();
        for (final StationExit exit : station.getExits()) {
            this.exits.put(exit.getName(), new ArrayList<>(exit.getDestinations()));
        }
    }

    public LocalStation() {
        super(
                0L, "?", 0x0, 0, 0, 0, 0
        );
        this.raw = null;
        this.zone = 0;
        this.exits = new HashMap<>();
    }

    public Station getRaw() {
        return raw;
    }
}
