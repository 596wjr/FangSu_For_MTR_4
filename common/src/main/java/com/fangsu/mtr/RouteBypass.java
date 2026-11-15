package com.fangsu.mtr;

import com.fangsu.mtr.rail.ServiceRail;
import org.mtr.core.data.Data;
import org.mtr.core.data.Station;
import org.mtr.core.data.Rail;
import net.minecraft.core.BlockPos;
import org.mtr.core.data.Position;

public final class RouteBypass {
    private RouteBypass() {}

    public static ServiceRail settings(Data data, ServiceStop.Bypass bypass) {
        final Rail rail = Data.tryGet(data.positionsToRail, position(bypass.start()), position(bypass.end()));
        final var settings = ServiceRail.get(rail);
        return settings != null && settings.kind() == ServiceRail.Kind.BYPASS ? settings : null;
    }

    public static long waypointId(ServiceStop.Bypass bypass) {
        final long first = bypass.start().asLong();
        final long second = bypass.end().asLong();
        // 快车经过的轨段拥有独立的寻路标识，可与同站普通站台相邻。
        return java.util.UUID.nameUUIDFromBytes(java.nio.ByteBuffer.allocate(16)
                .putLong(Math.min(first, second)).putLong(Math.max(first, second)).array()).getMostSignificantBits();
    }

    public static boolean sameStation(Station station, ServiceStop.Bypass bypass) {
        if (station == null) {
            return false;
        }
        final BlockPos start = bypass.start();
        final BlockPos end = bypass.end();
        return station.inArea(position(start)) && station.inArea(position(end));
    }

    public static Position position(BlockPos pos) {
        return new Position(pos.getX(), pos.getY(), pos.getZ());
    }

    public static BlockPos blockPos(Position pos) {
        return new BlockPos(Math.toIntExact(pos.getX()), Math.toIntExact(pos.getY()), Math.toIntExact(pos.getZ()));
    }
}
