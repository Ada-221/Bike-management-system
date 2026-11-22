package com.cebp.fleet.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxEventPublisher implements EventPublisher {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OutboxEventPublisher(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String topic, String key, Object event) {
        FleetEvent fleetEvent = (FleetEvent) event;
        try {
            jdbc.update("""
                    insert into fleet_outbox (event_id, topic, vehicle_id, body, state_revision)
                    values (?, ?, ?, cast(? as jsonb), ?)
                    """, fleetEvent.eventId(), topic, key, json.writeValueAsString(event),
                    fleetEvent.stateRevision());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize fleet event " + fleetEvent.eventId(), e);
        }
    }
}
