package com.cebp.fleet.zone;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A named circular area (table zones). Read-only in this service for now. */
@Entity
@Table(name = "zones")
public class Zone {

    @Id
    private String id;
    private String name;
    private double centerLatitude;
    private double centerLongitude;
    private int radiusMeters;
    private Instant createdAt;

    protected Zone() {
        // required by JPA
    }

    public Zone(String id, String name, double centerLatitude, double centerLongitude, int radiusMeters) {
        this.id = id;
        this.name = name;
        this.centerLatitude = centerLatitude;
        this.centerLongitude = centerLongitude;
        this.radiusMeters = radiusMeters;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public double getCenterLatitude() {
        return centerLatitude;
    }

    public double getCenterLongitude() {
        return centerLongitude;
    }

    public int getRadiusMeters() {
        return radiusMeters;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
