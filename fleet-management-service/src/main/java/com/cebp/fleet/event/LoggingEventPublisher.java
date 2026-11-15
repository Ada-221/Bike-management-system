package com.cebp.fleet.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Stand-in publisher that only writes the event to the log. Active unless the
 * "kafka" profile is on, where the real Kafka adapter will take over.
 */
@Component
@Profile("!kafka")
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    private final ObjectMapper objectMapper;

    public LoggingEventPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(String topic, String key, Object event) {
        try {
            log.info("EVENT topic={} key={} payload={}", topic, key, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            log.warn("EVENT topic={} key={} could not be serialized: {}", topic, key, e.getMessage());
        }
    }
}
