package com.aegispay.common.kafka;

import com.aegispay.common.events.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

/**
 * Converts {@link EventEnvelope} instances to and from JSON strings.
 *
 * <p><strong>Why plain JSON strings on the wire instead of Spring Kafka's
 * {@code JsonSerializer}/{@code JsonDeserializer}.</strong> Those helpers embed
 * the producer's fully-qualified Java class name in a record header and use it to
 * reconstruct the object on the consumer. That couples every consumer to the
 * producer's package layout: renaming or moving a payload class breaks
 * deserialisation of events already sitting in the log, and it also means a
 * malicious or buggy producer can influence which class the consumer
 * instantiates. Serialising to a string and having each consumer state the type
 * it expects removes both problems, keeps events readable in
 * {@code kafka-console-consumer}, and makes the payload contract explicit at
 * every call site.
 *
 * <p>Unknown fields are tolerated on read (see {@code @JsonIgnoreProperties} on
 * the payload records), which is what allows a producer to add a field before
 * consumers are redeployed.
 */
@Component
public class KafkaEventCodec {

    private final ObjectMapper objectMapper;

    public KafkaEventCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Serialises an envelope for publication.
     *
     * <p>Note on Jackson 3 (which Spring Framework 7 / Boot 4 use): its exceptions
     * are unchecked, and the Jackson 2 {@code JsonProcessingException} no longer
     * exists. {@link JacksonException} is still caught explicitly rather than left
     * to propagate, so every failure leaves this class as the platform's own
     * {@link EventSerializationException} — which is what the Kafka error handler
     * classifies as non-retryable.
     */
    public String encode(EventEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JacksonException ex) {
            // A payload we cannot serialise is a programming error, not a
            // transient fault: failing fast prevents a silent event loss.
            throw new EventSerializationException(
                    "Failed to serialise event " + envelope.eventType() + "/" + envelope.eventId(), ex);
        }
    }

    /**
     * Deserialises a record value into an envelope with a payload of
     * {@code payloadType}.
     *
     * @throws EventSerializationException when the record is not valid JSON or the
     *                                     payload does not fit the expected type;
     *                                     the caller's error handler routes such
     *                                     records straight to the DLQ because
     *                                     retrying will never help
     */
    public <T> EventEnvelope<T> decode(String json, Class<T> payloadType) {
        try {
            JavaType type = objectMapper.getTypeFactory()
                    .constructParametricType(EventEnvelope.class, payloadType);
            return objectMapper.readValue(json, type);
        } catch (JacksonException ex) {
            throw new EventSerializationException(
                    "Failed to deserialise event into EventEnvelope<" + payloadType.getSimpleName() + ">", ex);
        }
    }

    /** Serialises an arbitrary object, used for outbox payload storage. */
    public String encodePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException ex) {
            throw new EventSerializationException("Failed to serialise payload", ex);
        }
    }

    /**
     * Marks a record as permanently unprocessable.
     *
     * <p>Deliberately not a subclass of any retryable exception type: the Kafka
     * error handler classifies this as non-retryable so a poison-pill record goes
     * to the DLQ on the first attempt instead of blocking its partition through
     * four pointless retries.
     */
    public static class EventSerializationException extends RuntimeException {
        public EventSerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
