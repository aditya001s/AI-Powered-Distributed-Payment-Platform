package com.aegispay.ledger;

import com.aegispay.common.domain.SharedEnums.PostingDirection;
import com.aegispay.common.events.EventEnvelope;
import com.aegispay.common.events.KafkaTopics;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentCompletedPayload;
import com.aegispay.common.events.payload.PaymentOutcomePayloads.PaymentRefundedPayload;
import com.aegispay.common.events.payload.LedgerPostingPayload;
import com.aegispay.common.events.payload.WalletMovementPayload;
import com.aegispay.common.money.Money;
import com.aegispay.common.security.Roles;
import com.aegispay.common.testsupport.TestJwtIssuer;
import com.aegispay.ledger.api.LedgerDtos.TrialBalanceResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Phase 3 to Phase 4 integration: business events arriving on Kafka become balanced postings.
 *
 * <p>This is the path that makes the deliverable true — "every successful payment produces a
 * balanced ledger entry" — so the events are published onto real topics through the same
 * {@code EventPublisher} the producing services use, and the assertions read the committed
 * journal rather than any in-process signal.
 *
 * <p>The scenarios are built to mirror what the platform actually emits: a deposit that funds a
 * wallet, a transfer between two wallets, and a refund that partially reverses it. Together they
 * exercise the reason the ledger consumes two different streams at all, and the reason it must
 * not double-count.
 */
class LedgerEventFlowIT extends AbstractLedgerIntegrationTest {

    private static final Duration BOOKING_TIMEOUT = Duration.ofSeconds(45);

    private static String adminToken() {
        return TestJwtIssuer.user("USR-ADMIN", Roles.ADMIN);
    }

    // ------------------------------------------------------------- publishing

    /** Publishes a deposit or withdrawal exactly as wallet-service does. */
    private void publishMovement(String walletTransactionId, String walletId,
                                 PostingDirection direction, long amountMinor) {
        WalletMovementPayload payload = new WalletMovementPayload(
                walletTransactionId, walletId, "USR-OWNER", direction,
                Money.ofMinor(amountMinor, INR), Money.ofMinor(amountMinor, INR),
                direction == PostingDirection.CREDIT ? "DEPOSIT" : "WITHDRAWAL",
                "integration test", Instant.now());

        publish(KafkaTopics.WALLET_MOVEMENT_RECORDED, EventEnvelope.of(
                KafkaTopics.WALLET_MOVEMENT_RECORDED, "Wallet", walletId, "wallet-service",
                payload));
    }

    /** Publishes a completed payment exactly as payment-service does. */
    private void publishCompletedPayment(String paymentId, String fromWallet, String toWallet,
                                         long amountMinor) {
        PaymentCompletedPayload payload = new PaymentCompletedPayload(
                paymentId, "TXN" + paymentId, "USR-PAYER", "USR-PAYEE",
                fromWallet, toWallet,
                Money.ofMinor(amountMinor, INR), Money.zero(INR),
                null, null, List.of(), null, 120L, Instant.now());

        publish(KafkaTopics.PAYMENT_COMPLETED, EventEnvelope.of(
                KafkaTopics.PAYMENT_COMPLETED, "Payment", paymentId, "payment-service", payload));
    }

    private void publishRefund(String paymentId, String refundId, String fromWallet,
                               String toWallet, long refundMinor, long originalMinor,
                               boolean full) {
        PaymentRefundedPayload payload = new PaymentRefundedPayload(
                paymentId, "TXN" + paymentId, refundId, "USR-PAYER",
                fromWallet, toWallet,
                Money.ofMinor(refundMinor, INR), Money.ofMinor(refundMinor, INR),
                Money.ofMinor(originalMinor, INR), full, "Customer request", "USR-PAYER",
                null, Instant.now());

        publish(KafkaTopics.PAYMENT_REFUNDED, EventEnvelope.of(
                KafkaTopics.PAYMENT_REFUNDED, "Payment", paymentId, "payment-service", payload));
    }

    private void awaitPosting(String ledgerTransactionId) {
        awaitUntil("the ledger to book " + ledgerTransactionId, BOOKING_TIMEOUT,
                () -> countEntriesWithKey(ledgerTransactionId) == 1);
    }

    // ---------------------------------------------------------- edge movements

    @Nested
    @DisplayName("wallet edge movements")
    class EdgeMovements {

        /**
         * Without the external-funding counterparty a deposit would be single-sided — money
         * appearing from nowhere — and the trial balance could never sum to zero. That is why
         * the ledger consumes wallet movements at all rather than only payment events.
         */
        @Test
        @DisplayName("a deposit books against external funding and balances")
        void depositBooksAgainstExternalFunding() {
            String walletId = newWalletId();

            publishMovement("TXN-DEP-1", walletId, PostingDirection.CREDIT, 1000 * RUPEE);
            awaitPosting("LTX-WLT-TXN-DEP-1");

            assertThat(postingTypeOf("LTX-WLT-TXN-DEP-1")).isEqualTo("DEPOSIT");
            assertThat(accountBalance(walletAccount(walletId)))
                    .as("we now owe the customer their deposit")
                    .isEqualTo(1000 * RUPEE);
            assertThat(accountBalance(FUNDING_INR))
                    .as("and we hold a matching asset")
                    .isEqualTo(1000 * RUPEE);
            assertThat(trialBalanceDifference(INR)).isZero();
        }

        @Test
        @DisplayName("a withdrawal books the exact reverse")
        void withdrawalBooksTheReverse() {
            String walletId = newWalletId();
            publishMovement("TXN-DEP-2", walletId, PostingDirection.CREDIT, 1000 * RUPEE);
            awaitPosting("LTX-WLT-TXN-DEP-2");

            publishMovement("TXN-WD-1", walletId, PostingDirection.DEBIT, 400 * RUPEE);
            awaitPosting("LTX-WLT-TXN-WD-1");

            assertThat(postingTypeOf("LTX-WLT-TXN-WD-1")).isEqualTo("WITHDRAWAL");
            assertThat(accountBalance(walletAccount(walletId))).isEqualTo(600 * RUPEE);
            assertThat(accountBalance(FUNDING_INR))
                    .as("the platform holds less after money leaves")
                    .isEqualTo(600 * RUPEE);
            assertThat(trialBalanceDifference(INR)).isZero();
        }

        /**
         * Kafka delivers at-least-once, and this service runs without Redis so
         * de-duplication is a no-op. The unique constraint on the derived key is therefore the
         * only thing preventing a double booking — which makes this the strongest form of the
         * test rather than the weakest.
         */
        @Test
        @DisplayName("a redelivered movement is booked exactly once")
        void redeliveredMovementBooksOnce() {
            String walletId = newWalletId();

            publishMovement("TXN-DEP-DUP", walletId, PostingDirection.CREDIT, 500 * RUPEE);
            awaitPosting("LTX-WLT-TXN-DEP-DUP");

            // The same wallet transaction announced twice, as a rebalance or retry would.
            publishMovement("TXN-DEP-DUP", walletId, PostingDirection.CREDIT, 500 * RUPEE);
            publishMovement("TXN-DEP-DUP", walletId, PostingDirection.CREDIT, 500 * RUPEE);

            // Give the consumer time to have processed and discarded both.
            awaitUntil("the duplicates to be consumed", Duration.ofSeconds(10),
                    () -> countEntriesWithKey("LTX-WLT-TXN-DEP-DUP") == 1
                            && accountBalance(walletAccount(walletId)) == 500 * RUPEE);

            assertThat(countEntriesWithKey("LTX-WLT-TXN-DEP-DUP")).isEqualTo(1);
            assertThat(accountBalance(walletAccount(walletId)))
                    .as("a redelivery must not credit the customer twice")
                    .isEqualTo(500 * RUPEE);
            assertThat(trialBalanceDifference(INR)).isZero();
        }
    }

    // ---------------------------------------------------------- payment flow

    @Nested
    @DisplayName("payment flow")
    class PaymentFlow {

        /**
         * The Phase 4 deliverable in one test: a successful payment produces a balanced ledger
         * entry, and the platform's total obligation is unchanged because an internal transfer
         * moves who is owed rather than how much.
         */
        @Test
        @DisplayName("a completed payment books a balanced transfer")
        void completedPaymentBooksATransfer() {
            String payer = newWalletId();
            String payee = newWalletId();
            String paymentId = newPaymentId();

            publishMovement("TXN-FUND-" + payer, payer, PostingDirection.CREDIT, 1000 * RUPEE);
            awaitPosting("LTX-WLT-TXN-FUND-" + payer);

            publishCompletedPayment(paymentId, payer, payee, 300 * RUPEE);
            awaitPosting("LTX-PAY-" + paymentId);

            assertThat(postingTypeOf("LTX-PAY-" + paymentId)).isEqualTo("TRANSFER");
            assertThat(accountBalance(walletAccount(payer))).isEqualTo(700 * RUPEE);
            assertThat(accountBalance(walletAccount(payee))).isEqualTo(300 * RUPEE);

            assertThat(accountBalance(walletAccount(payer)) + accountBalance(walletAccount(payee)))
                    .as("an internal transfer must not change what the platform owes in total")
                    .isEqualTo(1000 * RUPEE);
            assertThat(accountBalance(FUNDING_INR))
                    .as("no money entered or left the platform")
                    .isEqualTo(1000 * RUPEE);
            assertThat(trialBalanceDifference(INR)).isZero();
        }

        /**
         * The gap this phase closed. Before Phase 4 a refund moved money and emitted nothing, so
         * the book of record would have shown the transfer and never its reversal.
         */
        @Test
        @DisplayName("a refund books the reverse of the transfer")
        void refundBooksTheReverse() {
            String payer = newWalletId();
            String payee = newWalletId();
            String paymentId = newPaymentId();

            publishMovement("TXN-FUND-R-" + payer, payer, PostingDirection.CREDIT, 1000 * RUPEE);
            awaitPosting("LTX-WLT-TXN-FUND-R-" + payer);
            publishCompletedPayment(paymentId, payer, payee, 400 * RUPEE);
            awaitPosting("LTX-PAY-" + paymentId);

            publishRefund(paymentId, "RFD-0001", payer, payee, 150 * RUPEE, 400 * RUPEE, false);
            awaitPosting("LTX-RFD-RFD-0001");

            assertThat(postingTypeOf("LTX-RFD-RFD-0001"))
                    .as("a refund is a real movement, not an annulment of the original posting")
                    .isEqualTo("REFUND");
            assertThat(accountBalance(walletAccount(payer))).isEqualTo(750 * RUPEE);
            assertThat(accountBalance(walletAccount(payee))).isEqualTo(250 * RUPEE);
            assertThat(trialBalanceDifference(INR)).isZero();
        }

        /**
         * Repeat partial refunds each get their own posting because the key is derived from the
         * refund ID, not the payment ID. Keying on the payment would silently discard the second
         * refund as a replay.
         */
        @Test
        @DisplayName("repeat partial refunds each book their own posting")
        void repeatPartialRefundsEachBook() {
            String payer = newWalletId();
            String payee = newWalletId();
            String paymentId = newPaymentId();

            publishMovement("TXN-FUND-P-" + payer, payer, PostingDirection.CREDIT, 1000 * RUPEE);
            awaitPosting("LTX-WLT-TXN-FUND-P-" + payer);
            publishCompletedPayment(paymentId, payer, payee, 500 * RUPEE);
            awaitPosting("LTX-PAY-" + paymentId);

            publishRefund(paymentId, "RFD-A", payer, payee, 200 * RUPEE, 500 * RUPEE, false);
            awaitPosting("LTX-RFD-RFD-A");
            publishRefund(paymentId, "RFD-B", payer, payee, 300 * RUPEE, 500 * RUPEE, true);
            awaitPosting("LTX-RFD-RFD-B");

            assertThat(accountBalance(walletAccount(payee)))
                    .as("the payee has given all of it back")
                    .isZero();
            assertThat(accountBalance(walletAccount(payer))).isEqualTo(1000 * RUPEE);
            assertThat(trialBalanceDifference(INR)).isZero();
        }

        /**
         * The whole point of drawing the ingest boundary on ownership. wallet-service announces
         * only edge movements; if it also announced a payment's transfer legs, this test would
         * see the transfer booked twice.
         */
        @Test
        @DisplayName("a payment's transfer is booked once, not once per stream")
        void transferIsNotDoubleCounted() {
            String payer = newWalletId();
            String payee = newWalletId();
            String paymentId = newPaymentId();

            publishMovement("TXN-FUND-D-" + payer, payer, PostingDirection.CREDIT, 1000 * RUPEE);
            awaitPosting("LTX-WLT-TXN-FUND-D-" + payer);
            publishCompletedPayment(paymentId, payer, payee, 250 * RUPEE);
            awaitPosting("LTX-PAY-" + paymentId);

            assertThat(countEntries())
                    .as("one deposit plus one transfer, and nothing else")
                    .isEqualTo(2);
            assertThat(accountBalance(walletAccount(payee))).isEqualTo(250 * RUPEE);
        }

        /**
         * The provider fee, and who bears it.
         *
         * <p><strong>The platform does.</strong> That is not an accounting preference, it is forced by
         * what the wallets actually did: wallet-service moves exactly the payment amount at capture,
         * so the payer's debit and the payee's credit must both be exactly that amount. Deducting the
         * fee from the payee's credit, or adding it to the payer's debit, would make the ledger claim
         * a movement the wallet never made — and customer-liability reconciliation would then fail on
         * every single payment that carried a fee.
         *
         * <p>So the fee becomes a platform expense funded from the balance the platform holds at that
         * provider: four legs, two of which touch no customer at all.
         */
        @Test
        @DisplayName("a payment's fee is booked as a platform expense, not taken from either party")
        void paymentFeeIsBookedAgainstThePlatform() {
            String payer = newWalletId();
            String payee = newWalletId();
            String paymentId = newPaymentId();

            publishMovement("TXN-FUND-F-" + payer, payer, PostingDirection.CREDIT, 1000 * RUPEE);
            awaitPosting("LTX-WLT-TXN-FUND-F-" + payer);

            long amountMinor = 300 * RUPEE;
            long feeMinor = 500L;
            publish(KafkaTopics.PAYMENT_COMPLETED, EventEnvelope.of(
                    KafkaTopics.PAYMENT_COMPLETED, "Payment", paymentId, "payment-orchestrator",
                    new PaymentCompletedPayload(
                            paymentId, "TXN" + paymentId, "USR-PAYER", "USR-PAYEE",
                            payer, payee,
                            Money.ofMinor(amountMinor, INR), Money.ofMinor(feeMinor, INR),
                            "SWIFTPAY", "SWIFTPAY-REF-1", List.of("SWIFTPAY"), null, 120L,
                            Instant.now())));
            awaitPosting("LTX-PAY-" + paymentId);

            // Both customers moved exactly the payment amount, fee or no fee.
            assertThat(accountBalance(walletAccount(payee)))
                    .as("the payee is credited the full amount; the fee is not theirs to pay")
                    .isEqualTo(amountMinor);
            assertThat(accountBalance(walletAccount(payer)))
                    .as("the payer is debited exactly what they sent")
                    .isEqualTo(1000 * RUPEE - amountMinor);

            // The fee lands entirely on the platform.
            assertThat(accountBalance(LedgerPostingPayload.providerFeeExpenseAccount(INR)))
                    .as("the fee is a cost the platform incurred")
                    .isEqualTo(feeMinor);
            assertThat(accountBalance(
                    LedgerPostingPayload.providerSettlementAccount("SWIFTPAY", INR)))
                    .as("funded from what the platform holds at that provider, so an asset falls")
                    .isEqualTo(-feeMinor);

            assertThat(trialBalanceDifference(INR))
                    .as("four legs, still balanced")
                    .isZero();
        }

        /**
         * A fee that names no provider cannot be booked at all.
         *
         * <p>The credit leg has to come from a specific provider's settlement balance, and there is
         * no defensible way to guess which. Booking it against a catch-all would misstate what the
         * platform is owed by each provider, which is the number used to check their statements.
         *
         * <p>Non-retryable, so it lands on the dead-letter topic for a human. What matters here is
         * that nothing was booked and the books stayed balanced.
         */
        @Test
        @DisplayName("a fee naming no provider is not booked, rather than booked wrongly")
        void feeWithoutAProviderIsNotBooked() {
            String payer = newWalletId();
            String payee = newWalletId();
            String paymentId = newPaymentId();

            publishMovement("TXN-FUND-N-" + payer, payer, PostingDirection.CREDIT, 1000 * RUPEE);
            awaitPosting("LTX-WLT-TXN-FUND-N-" + payer);

            publish(KafkaTopics.PAYMENT_COMPLETED, EventEnvelope.of(
                    KafkaTopics.PAYMENT_COMPLETED, "Payment", paymentId, "payment-orchestrator",
                    new PaymentCompletedPayload(
                            paymentId, "TXN" + paymentId, "USR-PAYER", "USR-PAYEE",
                            payer, payee,
                            Money.ofMinor(300 * RUPEE, INR), Money.ofMinor(500L, INR),
                            null, null, List.of(), null, 120L, Instant.now())));

            // Nothing should appear. Waiting a fixed moment is acceptable here because the
            // assertion is about absence, and the deposit above proves the consumer is live.
            awaitUntil("the consumer to process and reject the event", Duration.ofSeconds(10),
                    () -> countEntriesWithKey("LTX-PAY-" + paymentId) == 0
                            && countEntries() == 1);

            assertThat(countEntriesWithKey("LTX-PAY-" + paymentId))
                    .as("a guessed settlement account would misstate what each provider owes us")
                    .isZero();
            assertThat(trialBalanceDifference(INR)).isZero();
        }
    }

    // -------------------------------------------------------- reconciliation

    @Nested
    @DisplayName("reconciliation over a realistic history")
    class Reconciliation {

        /**
         * The figure that makes cross-service reconciliation possible. It is only comparable to
         * wallet-service's own total because the ledger books deposits and withdrawals as well as
         * transfers; a ledger fed by payment events alone would be short by every deposit ever
         * made.
         */
        @Test
        @DisplayName("customer liability equals the sum of deposits less withdrawals")
        void customerLiabilityMatchesEdgeMovements() {
            String walletA = newWalletId();
            String walletB = newWalletId();

            publishMovement("TXN-REC-1", walletA, PostingDirection.CREDIT, 1000 * RUPEE);
            publishMovement("TXN-REC-2", walletB, PostingDirection.CREDIT, 500 * RUPEE);
            awaitPosting("LTX-WLT-TXN-REC-1");
            awaitPosting("LTX-WLT-TXN-REC-2");

            String paymentId = newPaymentId();
            publishCompletedPayment(paymentId, walletA, walletB, 200 * RUPEE);
            awaitPosting("LTX-PAY-" + paymentId);

            publishMovement("TXN-REC-3", walletB, PostingDirection.DEBIT, 100 * RUPEE);
            awaitPosting("LTX-WLT-TXN-REC-3");

            TrialBalanceResponse balance = get("/api/ledger/admin/trial-balance?currency=INR",
                    adminToken(), TrialBalanceResponse.class).body();

            assertThat(balance).isNotNull();
            assertThat(balance.balanced()).isTrue();
            assertThat(balance.differenceMinor()).isZero();
            assertThat(balance.accountsAgreeWithJournal())
                    .as("the cached account totals must agree with the journal lines")
                    .isTrue();

            // Deposited 1500, withdrew 100. The transfer moved value between customers without
            // changing the total owed.
            assertThat(balance.customerLiability().amountMinor()).isEqualTo(1400 * RUPEE);
            assertThat(accountBalance(FUNDING_INR))
                    .as("platform assets must match platform obligations")
                    .isEqualTo(1400 * RUPEE);
        }

        @Test
        @DisplayName("the whole-ledger report finds nothing wrong with a sound ledger")
        void soundLedgerReportsNoFindings() {
            String walletId = newWalletId();
            publishMovement("TXN-SOUND-1", walletId, PostingDirection.CREDIT, 750 * RUPEE);
            awaitPosting("LTX-WLT-TXN-SOUND-1");

            Response<String> response = get("/api/ledger/admin/reconcile", adminToken(),
                    String.class);

            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.body()).isNotNull()
                    .contains("\"consistent\":true")
                    .contains("\"findings\":[]");
        }
    }
}
