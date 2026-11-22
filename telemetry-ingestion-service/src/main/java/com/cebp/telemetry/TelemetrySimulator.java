package com.cebp.telemetry;

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
    private final String fixedStart;
    private final String replayAt;

    TelemetrySimulator(TelemetryIngestion ingestion,
                       @Value("${telemetry.simulator.vehicle-id:veh_001}") String vehicleId,
                       @Value("${telemetry.simulator.scenario:baseline}") String scenario,
                       @Value("${telemetry.simulator.start-at:}") String fixedStart,
                       @Value("${telemetry.simulator.replay-at:2026-11-05T20:00:00Z}") String replayAt) {
        this.ingestion = ingestion;
        this.vehicleId = vehicleId;
        this.scenario = scenario;
        this.fixedStart = fixedStart;
        this.replayAt = replayAt;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if ("replay".equals(scenario)) {
            Instant start = Instant.parse(replayAt);
            for (TelemetryMeasurement reading : TelemetryScenarios.forName(scenario, vehicleId, start)) {
                ingestion.publishReplay(reading, start.plusSeconds(6)).get();
            }
            return;
        }
        Instant start = fixedStart.isBlank() ? Instant.now()
                : Instant.parse(fixedStart);

        if ("fault".equals(scenario)) {
            ingestion.publishFault(vehicleId, start, "BRAKE_SENSOR", "Simulated brake sensor fault").get();
            System.out.println("Published fault telemetry for " + vehicleId);
            return;
        }
        List<TelemetryMeasurement> readings = TelemetryScenarios.forName(scenario, vehicleId, start);
        for (TelemetryMeasurement reading : readings) {
            if ("duplicate-event".equals(scenario)) {
                ingestion.publishExactDuplicateEvents(reading).get();
            } else {
                ingestion.publish(reading).get();
            }
            System.out.println("Published " + scenario + " telemetry for " + reading.vehicleId()
                    + " measured at " + reading.measuredAt()
                    + " with battery " + reading.batteryPercent() + "%");
        }
    }
}
