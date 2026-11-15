package com.cebp.telemetry;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

@SpringBootApplication
public class TelemetryApplication {
    static final String VEHICLE_TOPIC = "fleet.vehicle";

    public static void main(String[] args) {
        SpringApplication.run(TelemetryApplication.class, args);
    }

    @Bean
    NewTopic vehicleTopic() {
        return TopicBuilder.name(VEHICLE_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
}