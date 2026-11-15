package com.cebp.fleet.event;

/**
 * Port for publishing domain events. The service depends on this interface only,
 * so the Kafka adapter (Person 2) can be added without touching business code.
 */
public interface EventPublisher {

    void publish(String topic, String key, Object event);
}
