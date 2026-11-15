package com.cebp.fleet.vehicle;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/** A physical e-bike or e-scooter (table vehicles). */
@Entity
@Table(name = "vehicles")
public class Vehicle {

    @Id
    private String id;
    private String label;

    @Enumerated(EnumType.STRING)
    private VehicleType type;

    @Enumerated(EnumType.STRING)
    private VehicleStatus status;

    private int batteryPercent;
    private double latitude;
    private double longitude;
    private String zoneId;

    /** measuredAt of the last applied battery reading (battery freshness). */
    private Instant lastBatteryAt;
    /** measuredAt of the last applied location reading (location freshness). */
    private Instant lastLocationAt;
    /** Activity only: last time any telemetry arrived. Never gates validity. */
    private Instant lastTelemetryAt;

    /** Optimistic locking. A wrapper type (not long) so Spring Data can tell a new entity from an existing one. */
    @Version
    private Long version;

    private Instant createdAt;
    private Instant updatedAt;

    protected Vehicle() {
        // required by JPA
    }

    /** A freshly registered vehicle: AVAILABLE, battery/location timestamps set to registration time. */
    public static Vehicle register(String id, String label, VehicleType type, int batteryPercent,
                                   double latitude, double longitude, String zoneId, Instant now) {
        Vehicle v = new Vehicle();
        v.id = id;
        v.label = label;
        v.type = type;
        v.status = VehicleStatus.AVAILABLE;
        v.batteryPercent = batteryPercent;
        v.latitude = latitude;
        v.longitude = longitude;
        v.zoneId = zoneId;
        v.lastBatteryAt = now;
        v.lastLocationAt = now;
        v.lastTelemetryAt = null;
        v.createdAt = now;
        v.updatedAt = now;
        return v;
    }

    public void rename(String newLabel, Instant now) {
        this.label = newLabel;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public VehicleType getType() {
        return type;
    }

    public VehicleStatus getStatus() {
        return status;
    }

    public int getBatteryPercent() {
        return batteryPercent;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public String getZoneId() {
        return zoneId;
    }

    public Instant getLastBatteryAt() {
        return lastBatteryAt;
    }

    public Instant getLastLocationAt() {
        return lastLocationAt;
    }

    public Instant getLastTelemetryAt() {
        return lastTelemetryAt;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
