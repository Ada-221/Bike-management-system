package com.cebp.fleet.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;

/** Unit-level check of the T1 policy. A broker-backed IT needs a new test dependency (see summary). */
class FleetKafkaErrorHandlingTest {
    private final ConsumerRecord<String, String> record =
            new ConsumerRecord<>("fleet.vehicle", 0, 7L, "veh_001", "{bad");

    @Test
    @SuppressWarnings("unchecked")
    void invalidRecordGoesToDeadLetterTopicWhenEnabled() {
        KafkaOperations<String, String> kafka = mock(KafkaOperations.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        DefaultErrorHandler handler = FleetKafkaErrorHandling.build(kafka, true, 10);

        boolean recovered = handler.handleOne(failure(new InvalidTelemetryRecordException("bad", new IllegalArgumentException())),
                record, mock(Consumer.class), mock(MessageListenerContainer.class));

        assertThat(recovered).isTrue();
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("fleet.vehicle.DLT");
        assertThat(sent.getValue().key()).isEqualTo("veh_001");
        assertThat(sent.getValue().value()).isEqualTo("{bad");
    }

    @Test
    @SuppressWarnings("unchecked")
    void transientFailureIsRetriedNotDeadLettered() {
        KafkaOperations<String, String> kafka = mock(KafkaOperations.class);
        DefaultErrorHandler handler = FleetKafkaErrorHandling.build(kafka, true, 10);

        boolean recovered = handler.handleOne(failure(new IllegalStateException("db down")),
                record, mock(Consumer.class), mock(MessageListenerContainer.class));

        assertThat(recovered).isFalse();
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void withDeadLetterDisabledEveryFailureIsRetried() {
        KafkaOperations<String, String> kafka = mock(KafkaOperations.class);
        DefaultErrorHandler handler = FleetKafkaErrorHandling.build(kafka, false, 10);

        boolean recovered = handler.handleOne(failure(new InvalidTelemetryRecordException("bad", null)),
                record, mock(Consumer.class), mock(MessageListenerContainer.class));

        assertThat(recovered).isFalse();
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void listenerSeparatesValidationFailuresFromTransientOnes() {
        FleetTelemetry telemetry = mock(FleetTelemetry.class);
        FleetTelemetryListener listener = new FleetTelemetryListener(telemetry);

        doThrow(new IllegalArgumentException("Invalid telemetry battery percentage")).when(telemetry).consume("veh_001", "{bad");
        assertThatThrownBy(() -> listener.consume(record)).isInstanceOf(InvalidTelemetryRecordException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class).hasMessageContaining("offset 7");

        doThrow(new IllegalStateException("connection refused")).when(telemetry).consume("veh_001", "{bad");
        assertThatThrownBy(() -> listener.consume(record)).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(InvalidTelemetryRecordException.class);
    }

    private static ListenerExecutionFailedException failure(Throwable cause) {
        return new ListenerExecutionFailedException("listener failed", cause);
    }
}
