package com.aegispay.orchestrator.provider;

import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.money.Money;
import com.aegispay.orchestrator.provider.PaymentProvider.AuthorizationRequest;
import com.aegispay.orchestrator.provider.PaymentProvider.AuthorizationResult;
import com.aegispay.orchestrator.provider.SimulationControls.FaultMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for the three simulated payment rails.
 *
 * <p><strong>Every provider here is a simulation.</strong> Nothing contacts a bank, a card network or
 * a UPI rail. What is faithfully modelled is the behaviour a caller has to defend against, and these
 * tests are about that behaviour rather than about the fake internals:
 *
 * <ul>
 *   <li>A <strong>decline</strong> is returned, so it is invisible to the retry and the circuit
 *       breaker. A working provider saying no must not be treated as a broken one.</li>
 *   <li>A <strong>fault</strong> is thrown, so both see it.</li>
 *   <li>A <strong>timeout</strong> leaves a real charge behind that the saga must discover and
 *       void. This is the case that makes naive compensation wrong, and it is simulated on
 *       purpose.</li>
 * </ul>
 */
class SimulatedProviderTest {

    private SwiftPayProvider swiftPay;
    private NexusPayProvider nexusPay;
    private PayBridgeProvider payBridge;

    @BeforeEach
    void setUp() {
        swiftPay = new SwiftPayProvider();
        nexusPay = new NexusPayProvider();
        payBridge = new PayBridgeProvider();
    }

    @Nested
    @DisplayName("capabilities")
    class Capabilities {

        /**
         * The three rails are deliberately different, and this is why.
         *
         * <p>One narrow and cheap, one broad and moderate, one universal and dear. If they all
         * accepted everything, the capability filter would never do any work on ordinary traffic and a
         * bug in it would only surface in contrived cases.
         */
        @Test
        @DisplayName("differ in reach, so the capability filter does real work")
        void reachDiffers() {
            Money largeInr = Money.of("120000.00", "INR");
            Money usd = Money.of("100.00", "USD");
            Money eur = Money.of("100.00", "EUR");

            assertThat(swiftPay.capabilities().supports(largeInr, PaymentType.P2P_TRANSFER))
                    .as("SwiftPay's ceiling is well below this")
                    .isFalse();
            assertThat(swiftPay.capabilities().supports(usd, PaymentType.P2P_TRANSFER))
                    .as("SwiftPay settles INR only")
                    .isFalse();

            assertThat(nexusPay.capabilities().supports(largeInr, PaymentType.P2P_TRANSFER)).isTrue();
            assertThat(nexusPay.capabilities().supports(usd, PaymentType.P2P_TRANSFER)).isTrue();
            assertThat(nexusPay.capabilities().supports(eur, PaymentType.P2P_TRANSFER))
                    .as("NexusPay does not reach EUR")
                    .isFalse();

            assertThat(payBridge.capabilities().supports(eur, PaymentType.P2P_TRANSFER))
                    .as("PayBridge is the last resort, so it must accept what the others cannot")
                    .isTrue();
        }

        /** Price order is what the router's fee weighting is calibrated against. */
        @Test
        @DisplayName("are priced in a strict order: SwiftPay < NexusPay < PayBridge")
        void feesAreOrdered() {
            assertThat(swiftPay.capabilities().feeBasisPoints())
                    .isLessThan(nexusPay.capabilities().feeBasisPoints());
            assertThat(nexusPay.capabilities().feeBasisPoints())
                    .isLessThan(payBridge.capabilities().feeBasisPoints());
        }

        /**
         * The fee is money, so it is computed in minor units and rounded once. Deriving it with
         * floating point would drift by a paisa on some amounts, and the ledger would then book a fee
         * that does not match what the provider charged.
         */
        @Test
        @DisplayName("charge an exact fee in minor units")
        void feesAreExact() {
            // 25 bp of ₹2,000.00 = ₹5.00
            assertThat(swiftPay.capabilities().feeFor(Money.of("2000.00", "INR")))
                    .isEqualTo(Money.of("5.00", "INR"));
            // 55 bp of ₹2,000.00 = ₹11.00
            assertThat(nexusPay.capabilities().feeFor(Money.of("2000.00", "INR")))
                    .isEqualTo(Money.of("11.00", "INR"));
            // 95 bp of ₹2,000.00 = ₹19.00
            assertThat(payBridge.capabilities().feeFor(Money.of("2000.00", "INR")))
                    .isEqualTo(Money.of("19.00", "INR"));
        }

        /**
         * A capability mismatch is the router's mistake, not the provider's.
         *
         * <p>Thrown as {@link IllegalArgumentException} rather than a {@link ProviderFaultException}
         * so it does not count against the provider's health or trip its breaker — taking a working
         * rail out of service because of our own routing bug would turn one defect into an outage.
         */
        @Test
        @DisplayName("refuse a charge they cannot process without blaming themselves for it")
        void mismatchIsNotAFault() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> swiftPay.authorize(
                            authorizeRequest(Money.of("100.00", "USD"))))
                    .withMessageContaining("should not have selected it");
        }
    }

    @Nested
    @DisplayName("authorising")
    class Authorising {

        @Test
        @DisplayName("returns a voidable handle and the fee it charged")
        void approvesWithAHandle() {
            AuthorizationResult result = nexusPay.authorize(
                    authorizeRequest(Money.of("2000.00", "INR")));

            assertThat(result.isApproved()).isTrue();
            AuthorizationResult.Approved approved = (AuthorizationResult.Approved) result;
            assertThat(approved.authorizationId())
                    .as("without a handle there would be nothing for compensation to void")
                    .startsWith("NEXUSPAY-AUTH-");
            assertThat(approved.providerReference()).startsWith("NEXUSPAY-REF-");
            assertThat(approved.fee()).isEqualTo(Money.of("11.00", "INR"));
            assertThat(nexusPay.holdsAuthorization(approved.authorizationId())).isTrue();
        }

        /**
         * A resubmission must not charge twice.
         *
         * <p>The saga passes its own saga id as the idempotency key, so a retry after a lost response
         * arrives with the same key. A real rail collapses that; so does this one, because a
         * simulation that double-charged would train the orchestrator against behaviour no real
         * provider has.
         */
        @Test
        @DisplayName("collapses a resubmission carrying the same idempotency key")
        void resubmissionIsCollapsed() {
            AuthorizationRequest request = authorizeRequest(Money.of("2000.00", "INR"));

            var first = (AuthorizationResult.Approved) nexusPay.authorize(request);
            var second = (AuthorizationResult.Approved) nexusPay.authorize(request);

            assertThat(second.authorizationId()).isEqualTo(first.authorizationId());
            assertThat(nexusPay.outstandingAuthorizationCount())
                    .as("one charge, not two")
                    .isEqualTo(1);
        }

        /**
         * SwiftPay's decline rule, standing in for the velocity and structuring checks a real rail
         * applies. Deterministic on purpose: a probabilistic rule would make every fallback test
         * flaky and a genuine regression indistinguishable from an unlucky run.
         */
        @Test
        @DisplayName("SwiftPay declines round amounts at or above 25,000")
        void swiftPayDeclineRule() {
            AuthorizationResult declined = swiftPay.authorize(
                    authorizeRequest(Money.of("25000.00", "INR")));

            assertThat(declined.isApproved()).isFalse();
            assertThat(((AuthorizationResult.Declined) declined).declineCode())
                    .isEqualTo("VELOCITY_LIMIT");

            // Just below the threshold, and just off a round thousand: both approve.
            assertThat(swiftPay.authorize(authorizeRequest(Money.of("24000.00", "INR")))
                    .isApproved()).isTrue();
            assertThat(swiftPay.authorize(authorizeRequest(Money.of("25000.50", "INR")))
                    .isApproved()).isTrue();
        }

        /**
         * A declined charge creates nothing, so there is nothing to compensate.
         *
         * <p>If a decline left a handle behind, every declined payment would enter compensation and
         * the operator's uncompensated queue would fill with non-events.
         */
        @Test
        @DisplayName("a decline leaves no authorisation behind")
        void declineLeavesNothing() {
            swiftPay.authorize(authorizeRequest(Money.of("30000.00", "INR")));

            assertThat(swiftPay.outstandingAuthorizationCount()).isZero();
        }

        /**
         * The two rails that decline nothing exist so a test can attribute an outcome unambiguously.
         *
         * <p>With three sets of decline rules in play, a failed payment could be explained by routing,
         * by a fault, or by an interaction between rules — and there would be no way to tell which.
         */
        @Test
        @DisplayName("NexusPay and PayBridge decline nothing of their own")
        void theOthersDeclineNothing() {
            for (Money amount : List.of(Money.of("25000.00", "INR"),
                    Money.of("100000.00", "INR"), Money.of("1.00", "INR"))) {
                assertThat(nexusPay.authorize(authorizeRequest(amount)).isApproved())
                        .as("NexusPay must approve %s", amount).isTrue();
                assertThat(payBridge.authorize(authorizeRequest(amount)).isApproved())
                        .as("PayBridge must approve %s", amount).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("fault injection")
    class Faults {

        /**
         * Counted down rather than random.
         *
         * <p>A test arms exactly the failures it needs and then gets normal behaviour back, so the
         * assertion is about the system under test rather than about a seed.
         */
        @Test
        @DisplayName("affects exactly the number of calls it was armed for")
        void countsDown() {
            nexusPay.controls().injectFault(FaultMode.UNREACHABLE, 2);

            assertThatExceptionOfType(ProviderFaultException.class)
                    .isThrownBy(() -> nexusPay.authorize(authorizeRequest(money())));
            assertThatExceptionOfType(ProviderFaultException.class)
                    .isThrownBy(() -> nexusPay.authorize(authorizeRequest(money())));

            assertThat(nexusPay.authorize(authorizeRequest(money())).isApproved())
                    .as("the third call is unaffected")
                    .isTrue();
            assertThat(nexusPay.controls().currentFaultMode()).isEqualTo(FaultMode.NONE);
        }

        /**
         * <strong>The distinction the whole retry policy rests on.</strong>
         *
         * <p>{@code UNREACHABLE} means the request never arrived, so retrying the same provider is
         * safe. {@code TIMEOUT} means the charge may have landed and only the acknowledgement was
         * lost, so retrying risks charging twice — the saga must fall back instead.
         */
        @Test
        @DisplayName("reports whether the same provider is safe to retry")
        void reportsRetrySafety() {
            nexusPay.controls().injectFault(FaultMode.UNREACHABLE, 1);
            ProviderFaultException unreachable = catchFault(nexusPay);
            assertThat(unreachable.isSafeToRetrySameProvider())
                    .as("nothing arrived, so nothing can have been charged")
                    .isTrue();

            nexusPay.controls().injectFault(FaultMode.TIMEOUT, 1);
            ProviderFaultException timeout = catchFault(nexusPay);
            assertThat(timeout.isSafeToRetrySameProvider())
                    .as("the charge may exist; retrying could authorise it twice")
                    .isFalse();
        }

        /**
         * <strong>The most important simulation in the project.</strong>
         *
         * <p>The provider authorises the charge and then fails to answer. The saga holds no
         * authorisation id, so there is nothing obvious to void — and without a lookup the charge
         * would sit at the rail indefinitely, referenced by nothing. That is the failure that makes
         * "assume nothing was charged" the wrong compensation strategy.
         */
        @Test
        @DisplayName("a timeout leaves a real charge behind, discoverable by idempotency key")
        void timeoutOrphansACharge() {
            AuthorizationRequest request = authorizeRequest(money());
            nexusPay.controls().injectFault(FaultMode.TIMEOUT, 1);

            assertThatExceptionOfType(ProviderFaultException.class)
                    .isThrownBy(() -> nexusPay.authorize(request));

            assertThat(nexusPay.outstandingAuthorizationCount())
                    .as("the charge exists even though the caller never learned its id")
                    .isEqualTo(1);

            Optional<String> orphaned = nexusPay.findAuthorizationId(request.idempotencyKey());
            assertThat(orphaned)
                    .as("this lookup is the difference between compensation that works and "
                            + "compensation that hopes")
                    .isPresent();

            nexusPay.voidAuthorization(orphaned.get());
            assertThat(nexusPay.outstandingAuthorizationCount()).isZero();
        }

        /**
         * A decline is not a fault, so it must not make a probe or a void look like a failure.
         * Otherwise arming a decline would take the provider out of service, which is precisely the
         * confusion the decline-versus-fault split exists to prevent.
         */
        @Test
        @DisplayName("an armed decline does not make a probe or a void fail")
        void declineDoesNotBreakOtherOperations() {
            nexusPay.controls().injectFaultIndefinitely(FaultMode.DECLINE);

            nexusPay.probe();
            nexusPay.voidAuthorization("NEXUSPAY-AUTH-UNKNOWN");

            AuthorizationResult result = nexusPay.authorize(authorizeRequest(money()));
            assertThat(result.isApproved()).isFalse();
        }

        /** A probe must be able to fail, or health monitoring would report everything healthy. */
        @Test
        @DisplayName("a probe fails while a fault is armed")
        void probeCanFail() {
            nexusPay.controls().injectFault(FaultMode.UNREACHABLE, 1);

            assertThatExceptionOfType(ProviderFaultException.class)
                    .isThrownBy(() -> nexusPay.probe());
            nexusPay.probe();
        }

        @Test
        @DisplayName("an indefinite fault persists until it is cleared")
        void indefiniteFaultPersists() {
            payBridge.controls().injectFaultIndefinitely(FaultMode.UPSTREAM_ERROR);

            for (int call = 0; call < 5; call++) {
                assertThatExceptionOfType(ProviderFaultException.class)
                        .isThrownBy(() -> payBridge.authorize(authorizeRequest(money())));
            }
            assertThat(payBridge.controls().remainingFaults())
                    .as("negative means until cleared, and must not drift towards zero")
                    .isNegative();

            payBridge.controls().clearFaults();
            assertThat(payBridge.authorize(authorizeRequest(money())).isApproved()).isTrue();
        }
    }

    @Nested
    @DisplayName("voiding")
    class Voiding {

        /**
         * Compensation legitimately runs more than once, and it also runs when the saga cannot tell
         * whether the original authorisation succeeded. A void for something that never existed is
         * therefore a normal occurrence, not an error.
         */
        @Test
        @DisplayName("an unknown authorisation is a no-op, not an error")
        void unknownVoidIsFine() {
            nexusPay.voidAuthorization("NEXUSPAY-AUTH-NEVEREXISTED");
            nexusPay.voidAuthorization(null);
            nexusPay.voidAuthorization("  ");
        }

        @Test
        @DisplayName("voiding twice is safe")
        void doubleVoidIsFine() {
            var approved = (AuthorizationResult.Approved) nexusPay.authorize(
                    authorizeRequest(money()));

            nexusPay.voidAuthorization(approved.authorizationId());
            nexusPay.voidAuthorization(approved.authorizationId());

            assertThat(nexusPay.outstandingAuthorizationCount()).isZero();
        }

        /**
         * The idempotency-key index has to be cleared too.
         *
         * <p>A stale entry would make {@code findAuthorizationId} hand back a handle for a charge that
         * no longer exists, and the next saga using that key would think it had an orphan to clean up.
         */
        @Test
        @DisplayName("a void also clears the idempotency-key lookup")
        void voidClearsTheKeyIndex() {
            AuthorizationRequest request = authorizeRequest(money());
            var approved = (AuthorizationResult.Approved) nexusPay.authorize(request);

            nexusPay.voidAuthorization(approved.authorizationId());

            assertThat(nexusPay.findAuthorizationId(request.idempotencyKey())).isEmpty();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Money money() {
        return Money.of("2000.00", "INR");
    }

    private static AuthorizationRequest authorizeRequest(Money amount) {
        return new AuthorizationRequest(
                "PAY-TEST0001", "TXN0001", amount, PaymentType.P2P_TRANSFER,
                "WAL-SOURCE", "WAL-DEST", "Dinner split",
                // The saga uses its own id as the provider-facing idempotency key.
                "SAGA-TEST0001");
    }

    private static ProviderFaultException catchFault(PaymentProvider provider) {
        try {
            provider.authorize(authorizeRequest(money()));
            throw new AssertionError("expected " + provider.id() + " to fault");
        } catch (ProviderFaultException ex) {
            return ex;
        }
    }
}
