package com.aegispay.orchestrator.client;

import com.aegispay.common.domain.FraudDecision;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.domain.SharedEnums.PaymentType;
import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.exception.DomainExceptions.UpstreamUnavailableException;
import com.aegispay.common.money.Money;
import com.aegispay.orchestrator.config.OrchestrationProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Asks fraud-service for a risk verdict on a payment.
 *
 * <h2>Why this call is synchronous</h2>
 *
 * <p>The saga cannot continue without a verdict — the whole point of the risk step is to gate the
 * provider authorisation. An event-driven request/reply would park the saga awaiting a response, and
 * the recovery sweep would then see a saga whose state had not advanced and treat it as stalled. Same
 * reasoning as the ledger call: when the next step genuinely depends on the answer, waiting for it is
 * simpler and more honest than pretending the flow is asynchronous.
 *
 * <h2>Why retrying is safe</h2>
 *
 * <p>Assessment is idempotent on the payment id: a second call returns the stored verdict rather than
 * producing a new one. That is the opposite of a provider authorisation, where a blind retry risks
 * charging twice — so this client retries freely where the provider gateway retries only faults known
 * to have happened before the request arrived.
 *
 * <h2>What happens when fraud-service cannot be reached</h2>
 *
 * <p>This throws, and the saga fails the payment as a retryable technical fault. That is deliberately
 * <em>stricter</em> than fraud-service's own behaviour when only the model is down.
 *
 * <p>The difference is whether a compensating control exists. A model outage still leaves the
 * deterministic rules running, and those cover the archetypes the model scores worst on — so falling
 * back there is a real, if reduced, assessment. A fraud-service outage leaves nothing: no score, no
 * rules, no verdict. Continuing then would not be a degraded assessment, it would be an unassessed
 * payment recorded as an approved one, which is a false statement in the audit trail of an automated
 * decision. Failing is recoverable — nothing was charged, the reservation is released, the customer
 * retries — and it makes the outage visible as failed payments instead of invisible as unprotected
 * ones.
 *
 * <p>An operator who prefers availability sets {@code aegis.orchestration.fraud.allow-unassessed-payments},
 * and the saga then records the payment explicitly as unassessed so the choice is visible on the
 * payment rather than implied by configuration.
 */
@Component
public class FraudClient {

    private static final Logger log = LoggerFactory.getLogger(FraudClient.class);

    /** Names the shared Resilience4j registries key this client's breaker and retry by. */
    private static final String INSTANCE = "fraud-service";

    private final RestClient client;
    private final String baseUrl;
    private final OrchestrationProperties properties;
    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;

    public FraudClient(@Qualifier("internalRestClient") RestClient client,
                       @Value("${aegis.clients.fraud-service-url:http://localhost:8086}")
                       String baseUrl,
                       OrchestrationProperties properties,
                       CircuitBreakerRegistry circuitBreakers,
                       RetryRegistry retries) {
        // Mutated rather than injected as-is, so the configured risk budget is the one actually
        // enforced. The shared internal client carries a single platform-wide read timeout; leaving
        // it in place would make aegis.orchestration.fraud.call-timeout a property that documents an
        // intention without implementing it, which is worse than having no property at all.
        // mutate() keeps the service-token and correlation-id interceptors.
        this.client = client.mutate()
                .requestFactory(requestFactory(properties.getFraud().getCallTimeout()))
                .build();
        this.baseUrl = baseUrl;
        this.properties = properties;
        this.circuitBreakers = circuitBreakers;
        this.retries = retries;
    }

    /**
     * Assesses a payment for fraud risk.
     *
     * <p>Behavioural aggregates are deliberately absent from the request. fraud-service computes them
     * from its own read model, so this service cannot influence a score by choosing what to report —
     * and the windows are anchored to {@code occurredAt} rather than to whenever this call happens to
     * run, which differs by however long the saga queued.
     *
     * @throws UpstreamUnavailableException when no verdict could be obtained
     */
    public AssessmentView assess(String paymentId,
                                 String transactionId,
                                 String initiatorUserId,
                                 String destinationWalletId,
                                 Money amount,
                                 PaymentType paymentType,
                                 RiskSignals riskSignals,
                                 Instant occurredAt) {

        AssessRequest request = new AssessRequest(
                paymentId, transactionId, initiatorUserId, destinationWalletId,
                amount.toDecimal(), amount.currency(), paymentType, riskSignals, occurredAt);

        CircuitBreaker breaker = circuitBreakers.circuitBreaker(INSTANCE);
        Retry retry = retries.retry(INSTANCE, this::retryConfig);

        try {
            return breaker.decorateSupplier(
                    Retry.decorateSupplier(retry, () -> post(request))).get();

        } catch (CallNotPermittedException ex) {
            // The breaker is open, so nothing was attempted. Reported as unavailable rather than
            // silently approving: the saga's configured policy decides what that means, not this
            // client.
            log.warn("fraud-service circuit breaker is {}; payment {} cannot be assessed",
                    breaker.getState(), paymentId);
            throw new UpstreamUnavailableException("fraud-service",
                    "circuit breaker is " + breaker.getState());
        }
    }

    /**
     * A request factory whose read timeout is the risk budget.
     *
     * <p>The connect timeout matters as much as the read one: a fraud-service whose pod is gone would
     * otherwise hold the payment for the OS-level connect timeout, which is far longer than the whole
     * saga can afford.
     */
    private static org.springframework.http.client.ClientHttpRequestFactory requestFactory(
            Duration timeout) {
        org.springframework.boot.http.client.HttpClientSettings settings =
                org.springframework.boot.http.client.HttpClientSettings.defaults()
                        .withTimeouts(timeout, timeout);
        return org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder.detect()
                .build(settings);
    }

    // --------------------------------------------------------------- internals

    private AssessmentView post(AssessRequest request) {
        try {
            return client.post()
                    .uri(baseUrl + "/internal/fraud/assess")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                        throw translate(response.getStatusCode(), request.paymentId());
                    })
                    .body(AssessmentView.class);

        } catch (com.aegispay.common.exception.AegisException ex) {
            throw ex;
        } catch (RestClientException ex) {
            // Connection refused, DNS failure, read timeout: all the same thing from here.
            throw new UpstreamUnavailableException("fraud-service",
                    "could not assess payment " + request.paymentId(), ex);
        }
    }

    /**
     * Maps an HTTP failure onto a domain exception.
     *
     * <p>Everything becomes {@code UpstreamUnavailableException}, including the 4xx cases, and that is
     * intentional. A 400 from here means this service built a request fraud-service would not accept —
     * a bug on our side, not a decision about the customer — and a 401 or 403 means the service
     * credential is wrong. Neither is something the payment should be refused for as though the
     * customer had done something; both are our fault, and the saga's unassessed-payment policy is the
     * right place to decide what happens next.
     */
    private RuntimeException translate(HttpStatusCode status, String paymentId) {
        if (status.value() == 401 || status.value() == 403) {
            return new UpstreamUnavailableException("fraud-service",
                    "service-to-service authentication failed; check INTERNAL_CLIENT_SECRET");
        }
        if (status.is4xxClientError()) {
            log.error("""
                            fraud-service rejected the assessment request for payment {} with HTTP {}. \
                            This is a request-shape bug in the orchestrator, not a decision about the \
                            payment.""",
                    paymentId, status.value());
        }
        return new UpstreamUnavailableException("fraud-service",
                "returned HTTP " + status.value() + " assessing payment " + paymentId);
    }

    /**
     * Retry policy.
     *
     * <p>Retries freely, which is safe because assessment is idempotent on the payment id. Jittered
     * backoff so a fleet of orchestrators recovering from a fraud-service restart does not synchronise
     * into retrying at the same instant and reproduce the overload.
     */
    private RetryConfig retryConfig() {
        OrchestrationProperties.Fraud config = properties.getFraud();
        return RetryConfig.custom()
                .maxAttempts(config.getMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        Duration.ofMillis(120), 2.0, 0.3))
                .retryExceptions(UpstreamUnavailableException.class)
                .failAfterMaxAttempts(true)
                .build();
    }

    // ------------------------------------------------------------------- wire

    /**
     * The assessment request.
     *
     * <p>Field names match {@code FraudDtos.AssessPaymentRequest} exactly. Deliberately a record here
     * rather than a {@code Map}: a typo in a map key would silently omit a field, and omitting
     * {@code riskSignals} would drop every device and location feature from the score without any
     * error to notice.
     */
    record AssessRequest(
            String paymentId,
            String transactionId,
            String initiatorUserId,
            String destinationWalletId,
            BigDecimal amount,
            String currency,
            PaymentType paymentType,
            RiskSignals riskSignals,
            Instant occurredAt
    ) {
    }

    /**
     * The verdict.
     *
     * <p>{@code degradedMode} changes how every other field reads. A {@code LOW} level with
     * {@code degradedMode: true} means "the model was unreachable and the rules found nothing", not
     * "this payment is safe" — so the saga records the flag on its own row rather than only logging
     * it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AssessmentView(
            String assessmentId,
            String paymentId,
            double fraudProbability,
            int riskScore,
            RiskLevel riskLevel,
            FraudDecision decision,
            List<String> reasons,
            List<String> ruleHits,
            String modelVersion,
            long scoringLatencyMs,
            boolean degradedMode,
            Instant evaluatedAt
    ) {

        /** Whether the payment may proceed to a provider without a human looking first. */
        public boolean clearsPayment() {
            return decision == FraudDecision.APPROVE || decision == FraudDecision.REVIEW;
        }

        /** A short reason for the step log and the failure record. */
        public String summary() {
            return reasons == null || reasons.isEmpty()
                    ? decision.name()
                    : reasons.getFirst();
        }
    }
}
