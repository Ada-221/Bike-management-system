package com.cebp.fleet.vehicle;

import com.cebp.fleet.event.Topics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Error policy for the fleet.vehicle consumer (DRAFT, see integration-review.md).
 *
 * <ul>
 *   <li>Transient failures (database or Kafka unavailable, lock timeouts...) are retried forever at a fixed
 *       interval, so no reading is lost and ordering per partition is kept.</li>
 *   <li>With the dead-letter flag on, an {@link InvalidTelemetryRecordException} is never retried: the record is
 *       published to {@code fleet.vehicle.DLT} and the consumer moves on to the next record.</li>
 *   <li>With the flag off, behavior is unchanged: every failure is retried forever.</li>
 * </ul>
 */
final class FleetKafkaErrorHandling {
    static final String DEAD_LETTER_TOPIC = Topics.FLEET_VEHICLE + ".DLT";

    private FleetKafkaErrorHandling() {
    }

    static DefaultErrorHandler build(KafkaOperations<String, String> kafka, boolean deadLetterEnabled,
                                     long retryIntervalMs) {
        if (!deadLetterEnabled) {
            return new DefaultErrorHandler(new FixedBackOff(retryIntervalMs, FixedBackOff.UNLIMITED_ATTEMPTS));
        }
        // Partition -1 lets the producer choose by key, so the DLT may have a different partition count.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, error) -> new TopicPartition(DEAD_LETTER_TOPIC, -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(retryIntervalMs, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.addNotRetryableExceptions(InvalidTelemetryRecordException.class);
        return handler;
    }
}
