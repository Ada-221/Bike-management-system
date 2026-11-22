package com.cebp.fleet.zone;

import com.cebp.fleet.common.Geo;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Works out which zone a point is in (decision D8): among the zones whose circle
 * contains the point, the one with the nearest center wins. No match means null.
 *
 * <p>Zones are read-only in this service, so the list is cached in memory and reloaded after a short TTL
 * ({@code fleet.zone-cache-ttl-seconds}, default 60; 0 disables caching).
 */
@Component
public class ZoneAssigner {

    private record Snapshot(List<Zone> zones, long loadedAtNanos) {}

    private final ZoneRepository zones;
    private final long ttlNanos;
    private final LongSupplier nanoClock;
    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    @Autowired
    public ZoneAssigner(ZoneRepository zones, @Value("${fleet.zone-cache-ttl-seconds:60}") long ttlSeconds) {
        this(zones, ttlSeconds, System::nanoTime);
    }

    ZoneAssigner(ZoneRepository zones, long ttlSeconds, LongSupplier nanoClock) {
        this.zones = zones;
        this.ttlNanos = Math.max(0, ttlSeconds) * 1_000_000_000L;
        this.nanoClock = nanoClock;
    }

    /** @return the zone id, or null when the point is outside every zone */
    public String zoneIdFor(double latitude, double longitude) {
        return nearestContaining(currentZones(), latitude, longitude);
    }

    /** Drops the cached zone list; the next lookup reads the database again. */
    public void invalidate() {
        cache.set(null);
    }

    private List<Zone> currentZones() {
        long now = nanoClock.getAsLong();
        Snapshot snapshot = cache.get();
        if (snapshot != null && ttlNanos > 0 && now - snapshot.loadedAtNanos() < ttlNanos) {
            return snapshot.zones();
        }
        List<Zone> loaded = List.copyOf(zones.findAll());
        cache.set(new Snapshot(loaded, now));
        return loaded;
    }

    public static String nearestContaining(List<Zone> candidates, double latitude, double longitude) {
        Zone best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Zone zone : candidates) {
            double distance = Geo.distanceMeters(
                    latitude, longitude, zone.getCenterLatitude(), zone.getCenterLongitude());
            if (distance <= zone.getRadiusMeters() && distance < bestDistance) {
                best = zone;
                bestDistance = distance;
            }
        }
        return best == null ? null : best.getId();
    }
}
