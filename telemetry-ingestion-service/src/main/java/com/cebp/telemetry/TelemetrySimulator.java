package com.cebp.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "telemetry.simulator.enabled", havingValue = "true")
class TelemetrySimulator implements ApplicationRunner {
    private final TelemetryIngestion ingestion;

    TelemetrySimulator(TelemetryIngestion ingestion) {
        this.ingestion = ingestion;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Instant start = Instant.now().minusSeconds(5);
        List<TelemetryMeasurement> measurements = List.of(
                new TelemetryMeasurement("veh_001", start, 75,
                        new BigDecimal("48.2102"), new BigDecimal("16.3598")),
                new TelemetryMeasurement("veh_001", start.plusSeconds(5), 74,
                        new BigDecimal("48.2104"), new BigDecimal("16.3600"))
        );

        for (TelemetryMeasurement measurement : measurements) {
            ingestion.publish(measurement).get();
            System.out.println("Published measurement for " + measurement.vehicleId()
                    + " with battery " + measurement.batteryPercent() + "%");
        }
    }
}