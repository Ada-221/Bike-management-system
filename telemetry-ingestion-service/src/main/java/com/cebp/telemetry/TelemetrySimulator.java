package com.cebp.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "telemetry.simulator.enabled", havingValue = "true")
class TelemetrySimulator implements ApplicationRunner {
    private final TelemetryIngestion ingestion;
    private final String vehicleId;
    private final String scenario;
    private final String replayAt;

    TelemetrySimulator(TelemetryIngestion ingestion,
                       @Value("${telemetry.simulator.vehicle-id:veh_001}") String vehicleId,
                       @Value("${telemetry.simulator.scenario:baseline}") String scenario,
                       @Value("${telemetry.simulator.replay-at:2026-11-05T20:00:00Z}") String replayAt) {
        this.ingestion = ingestion;
        this.vehicleId = vehicleId;
        this.scenario = scenario;
        this.replayAt = replayAt;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if ("fault".equals(scenario)) {
            ingestion.publishFault(vehicleId, Instant.now(), "BRAKE_SENSOR", "Simulated brake sensor fault").get();
            return;
        }
        if ("replay".equals(scenario)) {
            replay(Instant.parse(replayAt));
            return;
        }
        if (!"baseline".equals(scenario)) throw new IllegalArgumentException("Unknown telemetry scenario: " + scenario);
        Instant start = Instant.now().minusSeconds(5);
        List<TelemetryMeasurement> measurements = List.of(
                new TelemetryMeasurement(vehicleId, start, 75,
                        new BigDecimal("48.2102"), new BigDecimal("16.3598")),
                new TelemetryMeasurement(vehicleId, start.plusSeconds(5), 74,
                        new BigDecimal("48.2104"), new BigDecimal("16.3600"))
        );

        for (TelemetryMeasurement measurement : measurements) {
            ingestion.publish(measurement).get();
            System.out.println("Published measurement for " + measurement.vehicleId()
                    + " with battery " + measurement.batteryPercent() + "%");
        }
    }

    private void replay(Instant start) throws Exception {
        Instant publishedAt = start.plusSeconds(6);
        TelemetryMeasurement first = new TelemetryMeasurement(vehicleId, start, 70,
                new BigDecimal("48.2101"), new BigDecimal("16.3599"));
        TelemetryMeasurement latest = new TelemetryMeasurement(vehicleId, start.plusSeconds(5), 65,
                new BigDecimal("48.2105"), new BigDecimal("16.3605"));
        TelemetryMeasurement late = new TelemetryMeasurement(vehicleId, start.plusSeconds(2), 68,
                new BigDecimal("48.2102"), new BigDecimal("16.3602"));
        for (TelemetryMeasurement measurement : List.of(first, latest, first, late)) {
            ingestion.publishReplay(measurement, publishedAt).get();
        }
    }
}
