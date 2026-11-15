package com.cebp.fleet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Business constants from application.yml (prefix "fleet").
 * Source: domain-model.md section 6.
 */
@ConfigurationProperties(prefix = "fleet")
public record FleetProperties(
        int minRentableBatteryPercent,
        int lowBatteryResolvePercent,
        int nearbyDefaultRadiusMeters,
        int nearbyMaxRadiusMeters,
        int listDefaultLimit,
        int listMaxLimit,
        int sseHeartbeatSeconds) {
}
