package com.aegispay.fraud.client;

import com.aegispay.common.observability.MetricNames;
import com.aegispay.fraud.config.FraudProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Calls ml-service for a calibrated fraud probability.
 *
 * <h2>Why this returns an Optional rather than throwing</h2>
 *
 * <p>A model that cannot be reached is an expected operating condition, not an error the caller
 * should be interrupted by. {@code Optional.empty()} means "score this on rules alone and mark the
 * verdict degraded", which is a decision the scoring service makes deliberately — see
 * {@code FraudProperties.Ml.rejectWhenUnavailable} for why the default is to fall back rather than
 * refuse every payment while the model is down.
 *
 * <h2>Why retrying is safe here, unlike a provider authorisation</h2>
 *
 * <p>Scoring is a pure function with no side effect: a duplicate call charges nobody and returns the
 * same answer. That is the opposite of {@code PaymentProvider.authorize}, where a blind retry risks
 * charging twice — so this client retries freely where the provider gateway retries only faults known
 * to have happened before the request arrived.
 *
 * <h2>Why a short timeout</h2>
 *
 * <p>This call sits on the payment's critical path, ahead of a provider authorisation that has a
 * three-second budget of its own. A slow model therefore eats the saga's headroom, and falling back to
 * rules in a fifth of a second is better for the customer than making every payment wait. The
 * circuit breaker then stops asking altogether once the model is clearly down, which is the whole
 * point of having one.
 *
 * <h2>The wire contract is fragile in one specific way</h2>
 *
 * <p>ml-service is a pydantic application whose default camelCase generator turns
 * {@code payment_count_1h} into {@code paymentCount1H} — with a capital H — while Jackson serialising
 * the field below produces {@code paymentCount1h}. The Python side pins explicit aliases for the six
 * affected fields and asserts them in a test; the field names here must match those exactly. It is
 * caught loudly rather than silently, because ml-service rejects unknown fields, but the failure
 * would be six features quietly absent from every score if it did not.
 */
@Component
public class MlScoringClient {

    private static final Logger log = LoggerFactory.getLogger(MlScoringClient.class);

    /** Names the shared Resilience4j registries key this client's breaker and retry by. */
    private static final String INSTANCE = "ml-service";

    private final RestClient client;
    private final String baseUrl;
    private final String apiKey;
    private final FraudProperties properties;
    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final MeterRegistry meterRegistry;

    public MlScoringClient(RestClient.Builder builder,
                           @Value("${aegis.clients.ml-service-url:http://localhost:8000}")
                           String baseUrl,
                           @Value("${aegis.fraud.ml.api-key:}") String apiKey,
                           FraudProperties properties,
                           CircuitBreakerRegistry circuitBreakers,
                           RetryRegistry retries,
                           MeterRegistry meterRegistry) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.properties = properties;
        this.circuitBreakers = circuitBreakers;
        this.retries = retries;
        this.meterRegistry = meterRegistry;

        // A dedicated builder rather than the shared internalRestClient: ml-service authenticates
        // with a static API key, not a client-credentials token, because it is a Python service with
        // no place in the platform's OAuth2 trust chain. Attaching a bearer token here would be
        // misleading about how it is actually secured.
        this.client = builder
                .requestFactory(requestFactory(properties.getMl().getTimeout()))
                .build();

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("""
                    No ML_API_KEY is configured, so calls to ml-service will be unauthenticated. \
                    Acceptable locally; in any shared environment an unauthenticated scoring \
                    endpoint lets anyone map the model's decision boundary.""");
        }
    }

    /**
     * A request factory whose read timeout is the scoring budget.
     *
     * <p>Both timeouts set to the same short value. The connect timeout matters as much as the read
     * one here: a model whose pod is gone would otherwise hold the payment for the OS-level connect
     * timeout, which is far longer than the whole saga can afford.
     */
    private static org.springframework.http.client.ClientHttpRequestFactory requestFactory(
            Duration timeout) {
        org.springframework.boot.http.client.HttpClientSettings settings =
                org.springframework.boot.http.client.HttpClientSettings.defaults()
                        .withTimeouts(timeout, timeout);
        return org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder.detect()
                .build(settings);
    }

    /**
     * Scores a payment, or reports that the model could not be reached.
     *
     * @return the score, or empty when ml-service was unavailable, too slow, or its breaker is open
     */
    public Optional<ScoreResponse> score(ScoreRequest request) {
        CircuitBreaker breaker = circuitBreakers.circuitBreaker(INSTANCE);
        Retry retry = retries.retry(INSTANCE, this::retryConfig);
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            ScoreResponse response = breaker.decorateSupplier(
                    Retry.decorateSupplier(retry, () -> post(request))).get();

            sample.stop(meterRegistry.timer(MetricNames.FRAUD_ML_CALL_LATENCY,
                    MetricNames.TAG_OUTCOME, MetricNames.OUTCOME_SUCCESS));
            return Optional.of(response);

        } catch (CallNotPermittedException ex) {
            // The breaker is open, so nothing was attempted. Exactly the value it adds: the payment
            // falls back to rules immediately instead of waiting out another timeout.
            sample.stop(meterRegistry.timer(MetricNames.FRAUD_ML_CALL_LATENCY,
                    MetricNames.TAG_OUTCOME, MetricNames.OUTCOME_CIRCUIT_OPEN));
            log.warn("ml-service circuit breaker is {}; scoring payment {} on rules alone",
                    breaker.getState(), request.paymentId());
            return Optional.empty();

        } catch (RuntimeException ex) {
            sample.stop(meterRegistry.timer(MetricNames.FRAUD_ML_CALL_LATENCY,
                    MetricNames.TAG_OUTCOME, MetricNames.OUTCOME_FAILURE));
            log.error("Could not score payment {} with the model, so the rules will decide it: {}",
                    request.paymentId(), ex.getMessage());
            return Optional.empty();
        }
    }

    /** Model metadata, for the admin view. Empty when the model is unreachable. */
    public Optional<Map<String, Object>> modelInfo() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = client.get()
                    .uri(baseUrl + "/model")
                    .headers(headers -> {
                        if (apiKey != null && !apiKey.isBlank()) {
                            headers.set("X-API-Key", apiKey);
                        }
                    })
                    .retrieve()
                    .body(Map.class);
            return Optional.ofNullable(body);
        } catch (RuntimeException ex) {
            log.debug("Could not read ml-service model metadata: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    // --------------------------------------------------------------- internals

    private ScoreResponse post(ScoreRequest request) {
        try {
            return client.post()
                    .uri(baseUrl + "/score")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (apiKey != null && !apiKey.isBlank()) {
                            headers.set("X-API-Key", apiKey);
                        }
                    })
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                        throw new MlUnavailableException(
                                "ml-service returned HTTP " + response.getStatusCode());
                    })
                    .body(ScoreResponse.class);

        } catch (MlUnavailableException ex) {
            throw ex;
        } catch (RestClientException ex) {
            // Connection refused, DNS failure, read timeout: all the same thing from here.
            throw new MlUnavailableException("could not reach ml-service: " + ex.getMessage(), ex);
        }
    }

    /**
     * Retry policy.
     *
     * <p>Retries on anything, which is safe precisely because scoring has no side effect. Jittered
     * backoff so a fleet of fraud-service instances recovering from a model restart does not
     * synchronise into retrying at the same instant and reproduce the overload.
     */
    private RetryConfig retryConfig() {
        FraudProperties.Ml config = properties.getMl();
        return RetryConfig.custom()
                .maxAttempts(config.getMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        config.getRetryInterval(), 2.0, 0.3))
                .retryExceptions(MlUnavailableException.class)
                .failAfterMaxAttempts(true)
                .build();
    }

    /** The model could not be reached or refused the request. */
    public static class MlUnavailableException extends RuntimeException {

        public MlUnavailableException(String message) {
            super(message);
        }

        public MlUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------- wire

    /**
     * The scoring request.
     *
     * <p><strong>Every field name here is the wire contract.</strong> The six with digits in them
     * match explicit aliases on the Python side; see the class javadoc for why they cannot be left to
     * either framework's camelCase convention. Field order is irrelevant, spelling is not.
     *
     * <p>Boxed types throughout, because {@code null} is a meaningful value: a brand-new account has
     * no average amount, and ml-service is trained to treat absence as its own signal. A primitive
     * {@code long} defaulting to 0 would tell the model "this account's average payment is nothing",
     * which is a different and false claim.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record ScoreRequest(
            String paymentId,
            String transactionId,

            Long amountMinor,
            String currency,
            String paymentType,
            Integer hourOfDay,
            Integer dayOfWeek,

            Long accountAgeDays,
            Long priorPaymentCount,
            Long priorChargebackCount,

            Long avgAmountMinor,
            Long maxAmountMinor,

            Long paymentCount1h,
            Long paymentCount24h,
            Long amountSum24hMinor,
            Long failedPaymentCount24h,
            Long secondsSinceLastPayment,

            Boolean isNewDevice,
            Long distinctDevices30d,
            Boolean isNewCountry,
            Long distinctCountries30d,
            String countryCode,
            Double distanceFromLastKm,
            Boolean hasGeo,

            Boolean isNewPayee,
            String payeeMerchantCategory
    ) {
    }

    /**
     * What the model measured.
     *
     * <p>Note what is absent: no risk level and no decision. Bucketing a probability and choosing
     * APPROVE / REVIEW / HOLD / REJECT needs the deterministic rules as well as the model, and only this service
     * has both — so ml-service deliberately returns measurement and nothing else, and retuning a
     * threshold is a change here rather than a model redeploy.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScoreResponse(
            double fraudProbability,
            int riskScore,
            String modelVersion,
            /** The exact vector scored, retained so a disputed decision can be reproduced. */
            Map<String, Object> features,
            List<Contribution> contributions,
            long scoringLatencyMs
    ) {
    }

    /**
     * One feature's effect on this payment's score.
     *
     * @param impactPoints signed score points: how far the score would fall if this feature were
     *                     unknown. Negative is worth showing too — "the device was already known" is
     *                     often exactly why a large payment was allowed.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contribution(
            String feature,
            String description,
            Double value,
            double impactPoints,
            String direction
    ) {
    }
}
