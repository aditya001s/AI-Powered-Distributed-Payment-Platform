package com.aegispay.common.kafka;

import com.aegispay.common.events.EventEnvelope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventConsumerSupportTest {

    private KafkaEventCodec codec;
    private ProcessedEventRegistry registry;
    private EventConsumerSupport support;
    private EventEnvelope<String> envelope;
    private ConsumerRecord<String, String> record;

    @BeforeEach
    void setUp() {
        codec = mock(KafkaEventCodec.class);
        registry = mock(ProcessedEventRegistry.class);
        support = new EventConsumerSupport(codec, registry, new SimpleMeterRegistry());
        envelope = new EventEnvelope<>("event-1", "test-event", 1, "Test", "aggregate-1",
                Instant.now(), "correlation-1", null, "test", "payload");
        record = new ConsumerRecord<>("test-event", 0, 1L, "aggregate-1", "json");
        when(codec.decode("json", String.class)).thenReturn(envelope);
    }

    @Test
    @DisplayName("marks an event only after the handler completes successfully")
    void marksAfterSuccessfulHandler() {
        @SuppressWarnings("unchecked")
        Consumer<EventEnvelope<String>> handler = mock(Consumer.class);
        when(registry.isProcessed("consumer", "event-1")).thenReturn(false);

        support.handle("consumer", record, String.class, handler);

        InOrder order = inOrder(handler, registry);
        order.verify(handler).accept(envelope);
        order.verify(registry).markProcessed("consumer", "event-1");
    }

    @Test
    @DisplayName("does not leave a success marker when the handler fails")
    void failureLeavesNoMarker() {
        @SuppressWarnings("unchecked")
        Consumer<EventEnvelope<String>> handler = mock(Consumer.class);
        when(registry.isProcessed("consumer", "event-1")).thenReturn(false);
        org.mockito.Mockito.doThrow(new IllegalStateException("database rolled back"))
                .when(handler).accept(envelope);

        assertThatThrownBy(() -> support.handle("consumer", record, String.class, handler))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database rolled back");

        verify(registry, never()).markProcessed("consumer", "event-1");
    }

    @Test
    @DisplayName("processes normally when the duplicate-cache read is unavailable")
    void cacheReadFailureFailsOpenToDurableHandler() {
        @SuppressWarnings("unchecked")
        Consumer<EventEnvelope<String>> handler = mock(Consumer.class);
        when(registry.isProcessed("consumer", "event-1"))
                .thenThrow(new IllegalStateException("redis unavailable"));

        support.handle("consumer", record, String.class, handler);

        verify(handler).accept(envelope);
        verify(registry).markProcessed("consumer", "event-1");
    }

    @Test
    @DisplayName("does not fail an already-committed handler when the success marker write fails")
    void cacheWriteFailureDoesNotRetryCommittedWork() {
        @SuppressWarnings("unchecked")
        Consumer<EventEnvelope<String>> handler = mock(Consumer.class);
        when(registry.isProcessed("consumer", "event-1")).thenReturn(false);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
                .when(registry).markProcessed("consumer", "event-1");

        support.handle("consumer", record, String.class, handler);

        verify(handler).accept(envelope);
    }

    @Test
    @DisplayName("skips a replay only when a prior successful marker exists")
    void skipsPreviouslySuccessfulReplay() {
        @SuppressWarnings("unchecked")
        Consumer<EventEnvelope<String>> handler = mock(Consumer.class);
        when(registry.isProcessed("consumer", "event-1")).thenReturn(true);

        support.handle("consumer", record, String.class, handler);

        verify(handler, never()).accept(envelope);
        verify(registry, never()).markProcessed("consumer", "event-1");
    }
}
