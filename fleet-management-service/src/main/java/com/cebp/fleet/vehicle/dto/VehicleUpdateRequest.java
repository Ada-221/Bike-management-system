package com.cebp.fleet.vehicle.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of PATCH /vehicles/{vehicleId}: only the label can change. */
public record VehicleUpdateRequest(
        @NotBlank @Size(max = 50) String label) {
}
