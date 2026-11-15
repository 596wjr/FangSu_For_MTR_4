package com.fangsu.mtr;

import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import com.fangsu.mixin.RoutePlatformDataAccess;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.LongUnaryOperator;
import java.util.function.BiPredicate;

public final class RouteServices {
    private RouteServices() {}

    public static ServiceStop relation(Route route) {
        for (final var stop : route.getRoutePlatforms()) {
            final var metadata = ServiceStop.get(stop);
            if (metadata != null && metadata.parentStop() != null) {
                return metadata;
            }
        }
        return null;
    }

    public static long platformId(RoutePlatformData stop) {
        return ((RoutePlatformDataAccess) (Object) stop).fangsu$getPlatformId();
    }

    public static boolean hasRunningSection(Route parent, Route child) {
        final var relation = relation(child);
        if (relation == null) {
            return true;
        }
        if (parent == null || parent.getRoutePlatforms().size() < 2) {
            return false;
        }
        if (relation.express()) {
            return true;
        }

        final var inherited = new HashSet<UUID>();
        for (final var stop : child.getRoutePlatforms()) {
            final var metadata = ServiceStop.get(stop);
            if (metadata != null && metadata.parentStop() != null) {
                inherited.add(metadata.parentStop());
            }
        }
        int matches = 0;
        for (final var stop : parent.getRoutePlatforms()) {
            final var metadata = ServiceStop.get(stop);
            if (metadata != null && inherited.remove(metadata.id()) && ++matches == 2) {
                return true;
            }
        }
        return false;
    }

    /** 母线条目决定顺序，子线各自保留目标站台与停靠覆盖。 */
    public static boolean synchronize(Route parent, Route child, LongUnaryOperator stationForPlatform,
                                      BiPredicate<Long, ServiceStop.Bypass> validBypass) {
        final var relation = relation(child);
        final var parentStops = parent.getRoutePlatforms();
        final var childStops = child.getRoutePlatforms();
        final var previous = new HashMap<UUID, RoutePlatformData>();
        for (final var stop : childStops) {
            final var metadata = ServiceStop.get(stop);
            if (metadata != null && metadata.parentStop() != null) {
                previous.put(metadata.parentStop(), stop);
            }
        }

        int first = 0;
        int last = parentStops.size() - 1;
        if (!relation.express()) {
            first = 0;
            while (first < parentStops.size() && !previous.containsKey(ServiceStop.ensure(parentStops.get(first)).id())) {
                first++;
            }
            while (last >= first && !previous.containsKey(ServiceStop.ensure(parentStops.get(last)).id())) {
                last--;
            }
            if (last <= first) {
                return invalidateChild(childStops);
            }
        }
        if (parentStops.size() < 2) {
            return invalidateChild(childStops);
        }

        final List<RoutePlatformData> updated = new ArrayList<>();
        for (int index = first; index <= last; index++) {
            final var parentStop = parentStops.get(index);
            final var parentMetadata = ServiceStop.ensure(parentStop);
            final var old = previous.get(parentMetadata.id());
            final var metadata = old == null ? parentMetadata.child(relation.parentRoute(), relation.express()) : ServiceStop.get(old);
            final long nativePlatform = platformId(parentStop);
            final long stationId = stationForPlatform.applyAsLong(nativePlatform);
            final boolean keepPlatform = old != null && metadata.platformOverride()
                    && stationId != 0 && stationId == stationForPlatform.applyAsLong(platformId(old));
            final boolean sameStation = old != null && stationId == stationForPlatform.applyAsLong(platformId(old));
            final long platform = keepPlatform ? platformId(old) : nativePlatform;
            final var bypass = sameStation && relation.express() ? metadata.bypass() : null;
            final var acceptedBypass = bypass != null && validBypass.test(nativePlatform, bypass) ? bypass : null;
            final var status = acceptedBypass != null ? RouteStopStatus.SKIPPED : metadata.statusOverride() == null
                    ? RouteStopStatus.of(parentStop) : metadata.statusOverride();
            final String destination = old == null ? parentStop.getCustomDestination() : old.getCustomDestination();
            final var next = metadata.override(keepPlatform, metadata.statusOverride(), acceptedBypass);
            if (old != null && platformId(old) == platform && status == RouteStopStatus.of(old)
                    && next.equals(ServiceStop.get(old)) && destination.equals(old.getCustomDestination())) {
                updated.add(old);
            } else {
                final var entry = new RoutePlatformData(platform);
                entry.setCustomDestination(destination);
                entry.platform = keepPlatform ? old.platform : parentStop.platform;
                ServiceStop.set(entry, next);
                RouteStopStatus.set(entry, status);
                updated.add(entry);
            }
        }
        if (childStops.equals(updated)) {
            return false;
        }
        childStops.clear();
        childStops.addAll(updated);
        return true;
    }

    private static boolean invalidateChild(List<RoutePlatformData> childStops) {
        // 保留原区间身份，仪表盘仍可打开并重新选段。
        boolean changed = false;
        for (final var stop : childStops) {
            if (RouteStopStatus.of(stop) != RouteStopStatus.DISABLED) {
                RouteStopStatus.set(stop, RouteStopStatus.DISABLED);
                changed = true;
            }
        }
        return changed;
    }
}
