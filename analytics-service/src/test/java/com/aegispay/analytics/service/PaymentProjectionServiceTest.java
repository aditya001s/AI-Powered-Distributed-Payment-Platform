package com.aegispay.analytics.service;

import com.aegispay.analytics.domain.PaymentFact;
import com.aegispay.analytics.domain.StreamState;
import com.aegispay.analytics.repository.PaymentFactRepository;
import com.aegispay.analytics.repository.StreamStateRepository;
import com.aegispay.common.domain.PaymentStatus;
import com.aegispay.common.events.KafkaTopics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The single writer, tested against the two things that actually go wrong.
 *
 * <p><strong>Concurrent inserts.</strong> Two listeners can process two events for the same payment at the
 * same moment — different topics, different partitions, different consumer threads. Both find no row and both
 * insert. A check-then-insert in application code cannot be made safe here, because the check and the insert
 * are separate statements and nothing serialises the threads between them. So the unique constraint decides
 * and the loser re-reads. That is the expected path, not an exception path, and it is asserted as such.
 *
 * <p><strong>The stream high-water mark.</strong> It has to advance on every applied event, including one
 * that changed nothing. A stream delivering only redeliveries is still a live stream, and reporting it stale
 * would send somebody investigating a consumer that is working perfectly.
 */
class PaymentProjectionServiceTest {

    private static final String PAYMENT = "pay_0000000000000042";
    private static final Instant OCCURRED = Instant.parse("2026-03-01T10:00:00Z");

    private PaymentFactRepository facts;
    private StreamStateRepository streams;
    private PaymentProjectionService projection;

    @BeforeEach
    void setUp() {
        facts = mock(PaymentFactRepository.class);
        streams = mock(StreamStateRepository.class);
        projection = new PaymentProjectionService(facts, streams);

        // Default: nothing exists yet, and save returns what it was given.
        when(facts.findByPaymentId(anyString())).thenReturn(Optional.empty());
        when(facts.saveAndFlush(any(PaymentFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(facts.save(any(PaymentFact.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(streams.findById(anyString())).thenReturn(Optional.empty());
        when(streams.saveAndFlush(any(StreamState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(streams.save(any(StreamState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ------------------------------------------------------------------------
    // creation
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("first event for a payment")
    class FirstEvent {

        /**
         * The row is seeded with the status the <em>calling listener</em> supplied, not with
         * {@code CREATED}. Any of the listeners can be the first to arrive, and assuming
         * {@code payment-created} led is the mistake the seed parameter exists to prevent.
         */
        @Test
        @DisplayName("seeds the row with the arriving event's own status and timestamp")
        void seedsFromTheArrivingEvent() {
            projection.apply(KafkaTopics.PAYMENT_COMPLETED, PAYMENT, PaymentStatus.COMPLETED, OCCURRED,
                    fact -> { });

            ArgumentCaptor<PaymentFact> seeded = ArgumentCaptor.forClass(PaymentFact.class);
            verify(facts).saveAndFlush(seeded.capture());

            assertThat(seeded.getValue().getPaymentId()).isEqualTo(PAYMENT);
            assertThat(seeded.getValue().getStatus())
                    .as("seeding as CREATED would report a settled payment as in-flight")
                    .isEqualTo(PaymentStatus.COMPLETED);
            assertThat(seeded.getValue().getCreatedAt())
                    .as("""
                            the event's own timestamp, not now(): charting by processing time would show a \
                            replayed month of history as one spike at the moment of the replay""")
                    .isEqualTo(OCCURRED);
        }

        @Test
        @DisplayName("runs the caller's mutation and saves the result")
        void appliesTheMutation() {
            AtomicBoolean ran = new AtomicBoolean(false);

            projection.apply(KafkaTopics.PAYMENT_CREATED, PAYMENT, PaymentStatus.CREATED, OCCURRED,
                    fact -> {
                        ran.set(true);
                        assertThat(fact.getPaymentId()).isEqualTo(PAYMENT);
                    });

            assertThat(ran).isTrue();
            verify(facts).save(any(PaymentFact.class));
        }
    }

    // ------------------------------------------------------------------------
    // existing row
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("subsequent events")
    class SubsequentEvents {

        @Test
        @DisplayName("merges into the existing row rather than inserting a second one")
        void mergesIntoExistingRow() {
            PaymentFact existing = PaymentFact.seed(PAYMENT, PaymentStatus.CREATED, OCCURRED);
            when(facts.findByPaymentId(PAYMENT)).thenReturn(Optional.of(existing));

            projection.apply(KafkaTopics.PAYMENT_COMPLETED, PAYMENT, PaymentStatus.COMPLETED,
                    OCCURRED.plusSeconds(4),
                    fact -> fact.applyStatus(PaymentStatus.COMPLETED, OCCURRED.plusSeconds(4)));

            verify(facts, never()).saveAndFlush(any(PaymentFact.class));
            verify(facts).save(existing);
            assertThat(existing.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        }

        /**
         * A redelivery changes nothing about the row, and must still move the stream forward. The distinction
         * matters operationally: "idle" and "stuck" look identical unless the projection records that it did
         * some work.
         */
        @Test
        @DisplayName("advances the stream even when the event changed nothing")
        void advancesStreamOnANoOp() {
            PaymentFact settled = PaymentFact.seed(PAYMENT, PaymentStatus.COMPLETED, OCCURRED);
            when(facts.findByPaymentId(PAYMENT)).thenReturn(Optional.of(settled));

            projection.apply(KafkaTopics.PAYMENT_COMPLETED, PAYMENT, PaymentStatus.COMPLETED, OCCURRED,
                    fact -> fact.applyStatus(PaymentStatus.COMPLETED, OCCURRED));

            // Nothing about the payment moved...
            assertThat(settled.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            // ...but the stream did.
            verify(streams).saveAndFlush(any(StreamState.class));
        }
    }

    // ------------------------------------------------------------------------
    // the insert race
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("two threads inserting the same payment")
    class InsertRace {

        /**
         * <strong>The expected path, not an error path.</strong> The loser of the constraint race re-reads and
         * applies its own fields to the winner's row, so no event is lost and no duplicate row exists.
         */
        @Test
        @DisplayName("the loser re-reads and merges into the winner's row")
        void losingTheRaceMergesIntoTheWinner() {
            PaymentFact winner = PaymentFact.seed(PAYMENT, PaymentStatus.CREATED, OCCURRED);

            when(facts.findByPaymentId(PAYMENT))
                    // First look: nothing there, so this thread tries to insert.
                    .thenReturn(Optional.empty())
                    // After the constraint fires: the other thread's row.
                    .thenReturn(Optional.of(winner));
            when(facts.saveAndFlush(any(PaymentFact.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_payment_facts_payment"));

            projection.apply(KafkaTopics.PAYMENT_COMPLETED, PAYMENT, PaymentStatus.COMPLETED,
                    OCCURRED.plusSeconds(4),
                    fact -> fact.applyStatus(PaymentStatus.COMPLETED, OCCURRED.plusSeconds(4)));

            assertThat(winner.getStatus())
                    .as("the losing thread's event must still be applied, to the row that won")
                    .isEqualTo(PaymentStatus.COMPLETED);
            verify(facts).save(winner);
        }

        /**
         * {@code saveAndFlush} rather than {@code save} is what makes the recovery possible: the violation has
         * to surface inside the {@code try}, not at the end of the transaction where it would be an
         * unrecoverable rollback instead of a re-read.
         */
        @Test
        @DisplayName("flushes the insert so the constraint fires where it can be recovered from")
        void insertIsFlushedImmediately() {
            projection.apply(KafkaTopics.PAYMENT_CREATED, PAYMENT, PaymentStatus.CREATED, OCCURRED,
                    fact -> { });

            verify(facts).saveAndFlush(any(PaymentFact.class));
        }

        /**
         * A constraint violation with no row to find afterwards is not the race — it is a different
         * constraint failing, and swallowing it would hide a genuine schema or data problem behind an event
         * that appeared to process successfully.
         */
        @Test
        @DisplayName("rethrows when the violation was not a lost race")
        void unrelatedViolationPropagates() {
            DataIntegrityViolationException cause =
                    new DataIntegrityViolationException("ck_payment_facts_score_matches_probability");

            when(facts.findByPaymentId(PAYMENT)).thenReturn(Optional.empty());
            when(facts.saveAndFlush(any(PaymentFact.class))).thenThrow(cause);

            assertThatThrownBy(() -> projection.apply(KafkaTopics.PAYMENT_CREATED, PAYMENT,
                    PaymentStatus.CREATED, OCCURRED, fact -> { }))
                    .as("a CHECK failure is a real defect and must reach the error handler")
                    .isSameAs(cause);
        }
    }

    // ------------------------------------------------------------------------
    // stream state
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("the stream high-water mark")
    class StreamHighWaterMark {

        @Test
        @DisplayName("creates the row for a topic seen for the first time")
        void createsStreamState() {
            projection.apply(KafkaTopics.FRAUD_CHECKED, PAYMENT, PaymentStatus.RISK_ASSESSED, OCCURRED,
                    fact -> { });

            ArgumentCaptor<StreamState> created = ArgumentCaptor.forClass(StreamState.class);
            verify(streams).saveAndFlush(created.capture());

            assertThat(created.getValue().getTopic()).isEqualTo(KafkaTopics.FRAUD_CHECKED);
            assertThat(created.getValue().getLastEventAt()).isEqualTo(OCCURRED);
        }

        @Test
        @DisplayName("advances an existing topic rather than replacing it")
        void advancesExistingStreamState() {
            StreamState state = StreamState.of(KafkaTopics.PAYMENT_CREATED, OCCURRED);
            when(streams.findById(KafkaTopics.PAYMENT_CREATED)).thenReturn(Optional.of(state));

            projection.apply(KafkaTopics.PAYMENT_CREATED, PAYMENT, PaymentStatus.CREATED,
                    OCCURRED.plusSeconds(30), fact -> { });

            assertThat(state.getLastEventAt()).isEqualTo(OCCURRED.plusSeconds(30));
            verify(streams).save(state);
            verify(streams, never()).saveAndFlush(any(StreamState.class));
        }

        /**
         * The mark only moves forward. A redelivered older event must not make a healthy projection look
         * staler than it is — the banner it drives is the one signal that the numbers on screen are old.
         */
        @Test
        @DisplayName("an older redelivery does not push the mark backwards")
        void olderEventDoesNotRegressTheMark() {
            StreamState state = StreamState.of(KafkaTopics.PAYMENT_CREATED, OCCURRED);
            when(streams.findById(KafkaTopics.PAYMENT_CREATED)).thenReturn(Optional.of(state));

            projection.apply(KafkaTopics.PAYMENT_CREATED, PAYMENT, PaymentStatus.CREATED,
                    OCCURRED.minusSeconds(600), fact -> { });

            assertThat(state.getLastEventAt())
                    .as("a replay of old history must not read as an outage")
                    .isEqualTo(OCCURRED);
            assertThat(state.getLastAppliedAt())
                    .as("but the service did do work, which is what separates idle from stuck")
                    .isNotNull();
        }

        /**
         * Two threads first-seeing the same topic at once. The row exists either way and the next event will
         * advance it, so there is nothing to recover and nothing to fail.
         */
        @Test
        @DisplayName("tolerates two threads creating the same topic row")
        void toleratesConcurrentStreamCreation() {
            when(streams.saveAndFlush(any(StreamState.class)))
                    .thenThrow(new DataIntegrityViolationException("projection_stream_state_pkey"));

            projection.apply(KafkaTopics.PAYMENT_CREATED, PAYMENT, PaymentStatus.CREATED, OCCURRED,
                    fact -> { });

            // No exception escaped, and the payment fact was still written.
            verify(facts).save(any(PaymentFact.class));
        }

        /**
         * Each topic gets its own row. A single global figure would only look stale once every consumer had
         * stopped; per topic, a broken {@code fraud-checked} consumer is visible while payments still flow.
         */
        @Test
        @DisplayName("tracks each topic separately")
        void tracksTopicsSeparately() {
            projection.apply(KafkaTopics.PAYMENT_CREATED, PAYMENT, PaymentStatus.CREATED, OCCURRED,
                    fact -> { });
            projection.apply(KafkaTopics.FRAUD_CHECKED, PAYMENT, PaymentStatus.RISK_ASSESSED, OCCURRED,
                    fact -> { });

            verify(streams).findById(KafkaTopics.PAYMENT_CREATED);
            verify(streams).findById(KafkaTopics.FRAUD_CHECKED);
            verify(streams, times(2)).saveAndFlush(any(StreamState.class));
        }
    }

    // ------------------------------------------------------------------------
    // unusable events
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("an event with no payment id")
    class UnusableEvents {

        /**
         * {@code IllegalArgumentException} on purpose: the shared Kafka error handler dead-letters it on the
         * first attempt rather than retrying. An event with no payment id will be just as unusable on the
         * fourth try, and retrying only delays the dead-letter record somebody needs in order to fix the
         * producer.
         */
        @Test
        @DisplayName("is refused non-retryably, naming the topic it came from")
        void refusesMissingPaymentId() {
            for (String missing : new String[] { null, "", "   " }) {
                assertThatThrownBy(() -> projection.apply(KafkaTopics.PAYMENT_CREATED, missing,
                        PaymentStatus.CREATED, OCCURRED, fact -> { }))
                        .isInstanceOf(IllegalArgumentException.class)
                        // The topic, because that is what identifies which producer to fix.
                        .hasMessageContaining(KafkaTopics.PAYMENT_CREATED);
            }

            verify(facts, never()).save(any(PaymentFact.class));
            verify(streams, never()).save(any(StreamState.class));
            verify(streams, never()).saveAndFlush(any(StreamState.class));
        }
    }

    // ------------------------------------------------------------------------
    // freshness read
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("newestEventApplied")
    class NewestEventApplied {

        @Test
        @DisplayName("returns the newest applied event")
        void returnsNewest() {
            when(facts.newestEventApplied()).thenReturn(Optional.of(OCCURRED));

            assertThat(projection.newestEventApplied()).isEqualTo(OCCURRED);
        }

        /**
         * Null, not {@code Instant.EPOCH} or now. Nothing projected yet is a distinct state: on a new
         * deployment it is entirely normal, and on an established one it is the most serious thing the
         * freshness figure can report. Substituting a timestamp would make one look like the other.
         */
        @Test
        @DisplayName("returns null when nothing has been projected")
        void returnsNullWhenEmpty() {
            when(facts.newestEventApplied()).thenReturn(Optional.empty());

            assertThat(projection.newestEventApplied())
                    .as("a substituted timestamp would report an empty projection as up to date")
                    .isNull();
        }
    }
}
