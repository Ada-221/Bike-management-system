package com.cebp.fleet.vehicle.dto;

import com.cebp.fleet.vehicle.VehicleType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /vehicles (VehicleCreate in fleet-api.yaml). */
public record VehicleCreateRequest(
        @NotBlank @Size(max = 50) String label,
        @NotNull VehicleType type,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
        @NotNull @Min(0) @Max(100) Integer batteryPercent) {
}
