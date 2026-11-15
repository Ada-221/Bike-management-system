package com.cebp.fleet.zone;

import com.cebp.fleet.common.Geo;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Works out which zone a point is in (decision D8): among the zones whose circle
 * contains the point, the one with the nearest center wins. No match means null.
 */
@Component
public class ZoneAssigner {

    private final ZoneRepository zones;

    public ZoneAssigner(ZoneRepository zones) {
        this.zones = zones;
    }

    /** @return the zone id, or null when the point is outside every zone */
    public String zoneIdFor(double latitude, double longitude) {
        return nearestContaining(zones.findAll(), latitude, longitude);
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
