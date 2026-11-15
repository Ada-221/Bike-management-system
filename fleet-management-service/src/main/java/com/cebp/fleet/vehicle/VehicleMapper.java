package com.cebp.fleet.vehicle;

import com.cebp.fleet.config.FleetProperties;
import com.cebp.fleet.vehicle.dto.VehicleResponse;
import org.springframework.stereotype.Component;

@Component
public class VehicleMapper {

    private final FleetProperties props;

    public VehicleMapper(FleetProperties props) {
        this.props = props;
    }

    public VehicleResponse toResponse(Vehicle v) {
        // "rentable" is derived, never stored (decision D3).
        boolean rentable = v.getStatus() == VehicleStatus.AVAILABLE
                && v.getBatteryPercent() >= props.minRentableBatteryPercent();
        return new VehicleResponse(
                v.getId(), v.getLabel(), v.getType(), v.getStatus(), v.getBatteryPercent(),
                v.getLatitude(), v.getLongitude(), v.getZoneId(), rentable,
                v.getLastBatteryAt(), v.getLastLocationAt(), v.getLastTelemetryAt(),
                v.getCreatedAt(), v.getUpdatedAt());
    }
}
