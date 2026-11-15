package com.cebp.telemetry;

import java.math.BigDecimal;
import java.time.Instant;

record TelemetryMeasurement(
        String vehicleId,
        Instant measuredAt,
        int batteryPercent,
        BigDecimal latitude,
        BigDecimal longitude
) {}