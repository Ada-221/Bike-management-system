package com.cebp.fleet.common;

/** Small geographic helpers (no PostGIS, see decision D7). */
public final class Geo {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private Geo() {
    }

    /** Haversine great-circle distance between two points, in meters. */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(Math.min(1, a)));
    }

    public static double latitudeDelta(double distanceMeters) {
        return Math.toDegrees(distanceMeters / EARTH_RADIUS_METERS);
    }

    /**
     * Half-width in degrees of longitude of a box that contains every point within {@code distanceMeters}
     * of {@code latitude}. Returns 180 (no useful bound) when the circle reaches a pole.
     */
    public static double longitudeDelta(double latitude, double distanceMeters) {
        double latitudeDelta = latitudeDelta(distanceMeters);
        if (Math.abs(latitude) + latitudeDelta >= 90) return 180;
        double cos = Math.cos(Math.toRadians(Math.abs(latitude) + latitudeDelta));
        // Using the latitude at the box edge farthest from the equator keeps the box a true superset of the
        // circle (meridians converge toward the poles).
        return Math.min(180, Math.toDegrees(distanceMeters / EARTH_RADIUS_METERS) / cos);
    }
}
