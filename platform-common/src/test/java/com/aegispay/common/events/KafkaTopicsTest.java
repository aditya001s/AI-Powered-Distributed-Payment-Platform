package com.aegispay.common.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the topic catalogue.
 *
 * <p>A topic name is a wire contract: renaming one silently orphans every event
 * already in the log and every deployed consumer. These tests pin the names and the
 * dead-letter convention.
 */
class KafkaTopicsTest {

    @Test
    @DisplayName("topic names are stable")
    void topicNamesAreStable() {
        // Asserted as literals on purpose. If a rename is genuinely intended, this
        // test must be updated deliberately rather than passing by accident.
        assertThat(KafkaTopics.PAYMENT_CREATED).isEqualTo("payment-created");
        assertThat(KafkaTopics.FRAUD_CHECKED).isEqualTo("fraud-checked");
        assertThat(KafkaTopics.PAYMENT_APPROVED).isEqualTo("payment-approved");
        assertThat(KafkaTopics.PAYMENT_REJECTED).isEqualTo("payment-rejected");
        assertThat(KafkaTopics.PAYMENT_COMPLETED).isEqualTo("payment-completed");
        assertThat(KafkaTopics.PAYMENT_FAILED).isEqualTo("payment-failed");
        assertThat(KafkaTopics.NOTIFICATION_REQUIRED).isEqualTo("notification-required");
    }

    @Test
    @DisplayName("every topic is registered for provisioning")
    void catalogueIsComplete() {
        // The provisioning bean iterates ALL. A topic missing from this list would
        // not be created, and Kafka would auto-create it with broker defaults we did
        // not choose (wrong partition count, wrong retention).
        assertThat(KafkaTopics.ALL).contains(
                KafkaTopics.PAYMENT_CREATED,
                KafkaTopics.FRAUD_CHECKED,
                KafkaTopics.PAYMENT_APPROVED,
                KafkaTopics.PAYMENT_REJECTED,
                KafkaTopics.PAYMENT_COMPLETED,
                KafkaTopics.PAYMENT_FAILED,
                KafkaTopics.PAYMENT_REFUNDED,
                KafkaTopics.LEDGER_POSTING_REQUESTED,
                KafkaTopics.LEDGER_POSTED,
                KafkaTopics.INVESTIGATION_REQUESTED,
                KafkaTopics.INVESTIGATION_COMPLETED,
                KafkaTopics.NOTIFICATION_REQUIRED);
    }

    @Test
    @DisplayName("the catalogue has no duplicates")
    void catalogueHasNoDuplicates() {
        assertThat(KafkaTopics.ALL).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("derives the dead-letter topic name")
    void derivesDeadLetterTopic() {
        assertThat(KafkaTopics.deadLetterFor("payment-created")).isEqualTo("payment-created.DLT");
    }

    /**
     * Idempotent so the error handler cannot produce {@code topic.DLT.DLT} if a
     * dead-lettered record is ever reprocessed and fails again.
     */
    @Test
    @DisplayName("dead-letter derivation is idempotent")
    void deadLetterDerivationIsIdempotent() {
        String once = KafkaTopics.deadLetterFor("payment-created");
        assertThat(KafkaTopics.deadLetterFor(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("recognises dead-letter topics")
    void recognisesDeadLetterTopics() {
        assertThat(KafkaTopics.isDeadLetter("payment-created.DLT")).isTrue();
        assertThat(KafkaTopics.isDeadLetter("payment-created")).isFalse();
        assertThat(KafkaTopics.isDeadLetter(null)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("allTopics")
    @DisplayName("topic names follow the kebab-case convention")
    void namesFollowConvention(String topic) {
        // Consistent naming matters operationally: mixed conventions make it
        // impossible to write a reliable ACL or metric pattern across topics.
        assertThat(topic)
                .as("topic '%s' should be lowercase kebab-case", topic)
                .matches("[a-z][a-z0-9-]*[a-z0-9]");
    }

    @ParameterizedTest
    @MethodSource("allTopics")
    @DisplayName("no topic is itself a dead-letter topic")
    void catalogueContainsNoDeadLetters(String topic) {
        // DLTs are derived, never declared. Declaring one would create a topic whose
        // own DLT is topic.DLT.DLT.
        assertThat(KafkaTopics.isDeadLetter(topic)).isFalse();
    }

    static List<String> allTopics() {
        return KafkaTopics.ALL;
    }
}
