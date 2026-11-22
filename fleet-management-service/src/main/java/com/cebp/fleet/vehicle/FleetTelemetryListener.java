package com.cebp.fleet.vehicle;

import com.cebp.fleet.event.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "fleet.kafka.enabled", havingValue = "true")
class FleetTelemetryListener {
    private final FleetTelemetry telemetry;

    FleetTelemetryListener(FleetTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @KafkaListener(topics = Topics.FLEET_VEHICLE)
    void consume(ConsumerRecord<String, String> record) {
        String where = "Fleet telemetry failed at " + record.topic() + "[" + record.partition()
                + "] offset " + record.offset();
        try {
            telemetry.consume(record.key(), record.value());
        } catch (IllegalArgumentException e) {
            // Validation failure: the same record will fail the same way every time.
            throw new InvalidTelemetryRecordException(where, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException(where, e);
        }
    }

    @Bean
    CommonErrorHandler fleetKafkaErrorHandler(KafkaTemplate<String, String> kafka,
                                              @Value("${fleet.kafka.dead-letter-enabled:false}") boolean deadLetter,
                                              @Value("${fleet.kafka.retry-interval-ms:5000}") long retryIntervalMs) {
        return FleetKafkaErrorHandling.build(kafka, deadLetter, retryIntervalMs);
    }
}
