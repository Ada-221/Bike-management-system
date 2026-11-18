package com.cebp.fleet.vehicle;

import com.cebp.fleet.event.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

@Component
@ConditionalOnProperty(name = "fleet.kafka.enabled", havingValue = "true")
class FleetTelemetryListener {
    private final FleetTelemetry telemetry;

    FleetTelemetryListener(FleetTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @KafkaListener(topics = Topics.FLEET_VEHICLE)
    void consume(ConsumerRecord<String, String> record) {
        try {
            telemetry.consume(record.key(), record.value());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Fleet telemetry failed at " + record.topic() + "["
                    + record.partition() + "] offset " + record.offset(), e);
        }
    }

    @Bean
    CommonErrorHandler fleetKafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(5000, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
