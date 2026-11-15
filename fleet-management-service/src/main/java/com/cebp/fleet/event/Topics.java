package com.cebp.fleet.event;

/** Kafka topics owned by fleet-management-service (events-owned.md). */
public final class Topics {

    public static final String FLEET_VEHICLE = "fleet.vehicle";
    public static final String FLEET_RENTAL = "fleet.rental";
    public static final String FLEET_MAINTENANCE = "fleet.maintenance";

    private Topics() {
    }
}
