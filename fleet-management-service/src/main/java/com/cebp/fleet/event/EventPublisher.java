package com.cebp.fleet.event;

/** Stages a domain event for delivery in the caller's transaction. */
public interface EventPublisher {

    void publish(String topic, String key, Object event);
}
