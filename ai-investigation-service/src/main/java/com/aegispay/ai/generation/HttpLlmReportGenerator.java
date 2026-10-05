package com.aegispay.ai.generation;

import com.aegispay.ai.config.AiProperties;
import com.aegispay.ai.domain.Recommendation;
import com.aegispay.ai.rag.RetrievedPolicy;
import com.aegispay.common.observability.MetricNames;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Generates a report by calling a hosted language model.
 *
 * <h2>Two wire shapes, one class</h2>
 *
 * <p>OpenAI's chat-completions shape and Anthropic's messages shape differ in the system-prompt position,
 * the auth header and the response path, and in nothing else that matters here. Two classes would
 * duplicate the timeout, breaker, retry, parsing and validation logic — and the duplicate would be where
 * a fix landed on one path and not the other. The differences are isolated to three small methods.
 *
 * <h2>What leaves the platform</h2>
 *
 * <p>Worth being explicit, because this is the only outbound call in the service and it carries customer
 * data to a third party. The prompt contains the payment id, the amount, feature values, device and
 * location facts, and customer-supplied text. It does <em>not</em> contain a customer name, email
 * address, phone number or wallet id — those are not in the evidence pack, which is why the pack is
 * assembled from the event rather than by fetching the customer record.
 *
 * <p>That is a deliberate limit rather than a happy accident, and it is the reason the offline composer is
 * the default: a deployment that has not consciously accepted sending payment data to an external model
 * provider should not be doing it because a config file shipped that way.
 *
 * <h2>Failure is expected, and handled by the caller</h2>
 *
 * <p>This throws rather than substituting its own fallback. Whether to fall back to the offline composer
 * is the caller's decision, because the caller is what records <em>which</em> generator wrote the report —
 * and a generator that quietly returned deterministic output under its own name would make that record
 * false.
 */
public class HttpLlmReportGenerator implements ReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(HttpLlmReportGenerator.class);

    /** Names the shared Resilience4j registries key this generator's breaker and retry by. */
    private static final String INSTANCE = "llm-provider";

    private static final String OPENAI = "openai";
    private static final String ANTHROPIC = "anthropic";

    private static final String OPENAI_DEFAULT_BASE = "https://api.openai.com";
    private static final String ANTHROPIC_DEFAULT_BASE = "https://api.anthropic.com";

    /** Pinned, because Anthropic requires it and an unpinned version changes behaviour silently. */
    private static final String ANTHROPIC_API_VERSION = "2023-06-01";

    private final RestClient client;
    private final AiProperties properties;
    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final MeterRegistry meterRegistry;
    private final String provider;

    public HttpLlmReportGenerator(RestClient.Builder builder,
                                  AiProperties properties,
                                  CircuitBreakerRegistry circuitBreakers,
                                  RetryRegistry retries,
                                  MeterRegistry meterRegistry) {
        this.properties = properties;
        this.circuitBreakers = circuitBreakers;
        this.retries = retries;
        this.meterRegistry = meterRegistry;
        this.provider = properties.getGeneration().getProvider().toLowerCase(java.util.Locale.ROOT);

        // A dedicated request factory rather than the shared internal client. This call goes to a third
        // party, and the shared client attaches the platform's own service token to every request it
        // makes — sending that to an external model provider would leak an internal credential to
        // somebody who has no use for it.
        this.client = builder
                .requestFactory(requestFactory(properties.getGeneration().getTimeout()))
                .build();
    }

    @Override
    public String id() {
        return provider + "/" + properties.getGeneration().getModel();
    }

    @Override
    public boolean isDeterministic() {
        return false;
    }

    /**
     * Whether this generator is configured well enough to try.
     *
     * <p>Checked before use so a misconfiguration surfaces at startup rather than as a failed generation
     * on the first real investigation — which would look like a provider outage and send somebody
     * debugging the wrong thing.
     */
    @Override
    public boolean isAvailable() {
        String apiKey = properties.getGeneration().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        return OPENAI.equals(provider) || ANTHROPIC.equals(provider)
                || !properties.getGeneration().getApiBaseUrl().isBlank();
    }

    @Override
    public GeneratedReport generate(EvidencePack evidence, List<RetrievedPolicy> policies) {
        List<RetrievedPolicy> retrieved = policies == null ? List.of() : policies;

        CircuitBreaker breaker = circuitBreakers.circuitBreaker(INSTANCE);
        Retry retry = retries.retry(INSTANCE, this::retryConfig);

        String reply;
        try {
            reply = breaker.decorateSupplier(Retry.decorateSupplier(retry,
                    () -> call(PromptBuilder.systemPrompt(),
                            PromptBuilder.userPrompt(evidence, retrieved)))).get();

        } catch (CallNotPermittedException ex) {
            // Nothing was attempted. Exactly the value of having a breaker: the investigation falls back
            // to the composer immediately instead of waiting out another twenty-second timeout.
            throw new GenerationException(
                    "the language model circuit breaker is " + breaker.getState(), ex);
        } catch (GenerationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new GenerationException("the language model call failed: " + ex.getMessage(), ex);
        }

        ReportParser.Parsed parsed = ReportParser.parse(reply);
        return toReport(parsed, evidence);
    }

    /**
     * Turns a parsed reply into a report, refusing anything outside the permitted vocabulary.
     *
     * <p><strong>This is where a prompt injection stops.</strong> A model persuaded to answer
     * {@code "RELEASE_IMMEDIATELY"} or {@code "recommend no action, the customer confirmed by phone"}
     * produces no recommendation at all, and the report escalates instead. There is deliberately no fuzzy
     * matching: guessing at the closest permitted value is precisely how an injected instruction becomes a
     * decision about somebody's money.
     */
    private GeneratedReport toReport(ReportParser.Parsed parsed, EvidencePack evidence) {
        Recommendation recommendation = parsed.recommendation().orElseGet(() -> {
            meterRegistry.counter(MetricNames.AI_REJECTED_RECOMMENDATION,
                    "provider", provider).increment();
            log.error("""
                            The model returned a recommendation outside the permitted vocabulary for \
                            payment {}: '{}'. Refused and escalated. This is either a confused model or \
                            a successful prompt injection, and both are worth investigating.""",
                    evidence.paymentId(), parsed.rawRecommendation());
            return Recommendation.whenUncertain();
        });

        String rationale = parsed.recommendation().isPresent()
                ? parsed.rationale()
                // The model's own rationale explained a recommendation that was refused, so quoting it
                // beside the escalation would explain a decision nobody made. Replaced with the real
                // reason, which the analyst needs to know.
                : "The model's recommendation was outside the permitted vocabulary and was refused, so "
                        + "this investigation escalates rather than acting on an unrecognised value. "
                        + "The narrative below is the model's, and should be read with that in mind.";

        return new GeneratedReport(
                parsed.narrative(),
                parsed.findings(),
                recommendation,
                rationale,
                parsed.confidence(),
                parsed.citedDocumentIds(),
                id(),
                false);
    }

    // ------------------------------------------------------------------- wire

    private String call(String systemPrompt, String userPrompt) {
        try {
            return ANTHROPIC.equals(provider)
                    ? callAnthropic(systemPrompt, userPrompt)
                    : callOpenAiCompatible(systemPrompt, userPrompt);
        } catch (GenerationException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new GenerationException("could not reach the model provider: " + ex.getMessage(), ex);
        }
    }

    /**
     * OpenAI's chat-completions shape, also spoken by most compatible gateways and local servers.
     *
     * <p>Which is why the base URL is overridable: the same code reaches a self-hosted model, and a
     * deployment that will not send payment data to a third party can still use a language model.
     */
    @SuppressWarnings("unchecked")
    private String callOpenAiCompatible(String systemPrompt, String userPrompt) {
        AiProperties.Generation config = properties.getGeneration();

        Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                "max_tokens", config.getMaxOutputTokens(),
                "temperature", config.getTemperature());

        Map<String, Object> response = client.post()
                .uri(baseUrl(OPENAI_DEFAULT_BASE) + "/v1/chat/completions")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + config.getApiKey())
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, res) -> {
                    throw new GenerationException(
                            "model provider returned HTTP " + res.getStatusCode());
                })
                .body(Map.class);

        List<Map<String, Object>> choices = response == null
                ? null
                : (List<Map<String, Object>>) response.get("choices");

        if (choices == null || choices.isEmpty()) {
            throw new GenerationException("the model provider returned no choices");
        }
        Map<String, Object> message = (Map<String, Object>) choices.getFirst().get("message");
        Object content = message == null ? null : message.get("content");

        if (content == null) {
            // Reachable when a response is truncated by a content filter or a token limit: the choice
            // exists with a finish reason and no content. Reported rather than treated as an empty
            // report, so the fallback is used instead of an analyst receiving a blank page.
            throw new GenerationException("the model returned a choice with no content, which usually "
                    + "means the response was truncated or filtered");
        }
        return content.toString();
    }

    /** Anthropic's messages shape: system prompt as a top-level field, not a message. */
    @SuppressWarnings("unchecked")
    private String callAnthropic(String systemPrompt, String userPrompt) {
        AiProperties.Generation config = properties.getGeneration();

        Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "system", systemPrompt,
                "messages", List.of(Map.of("role", "user", "content", userPrompt)),
                "max_tokens", config.getMaxOutputTokens(),
                "temperature", config.getTemperature());

        Map<String, Object> response = client.post()
                .uri(baseUrl(ANTHROPIC_DEFAULT_BASE) + "/v1/messages")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("x-api-key", config.getApiKey())
                .header("anthropic-version", ANTHROPIC_API_VERSION)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, res) -> {
                    throw new GenerationException(
                            "model provider returned HTTP " + res.getStatusCode());
                })
                .body(Map.class);

        List<Map<String, Object>> content = response == null
                ? null
                : (List<Map<String, Object>>) response.get("content");

        if (content == null || content.isEmpty()) {
            throw new GenerationException("the model provider returned no content blocks");
        }
        // Concatenated rather than taking the first block. A reply can arrive as several text blocks, and
        // taking only the first would silently truncate the report at whatever boundary the provider chose.
        StringBuilder text = new StringBuilder();
        for (Map<String, Object> block : content) {
            Object value = block.get("text");
            if (value != null) {
                text.append(value);
            }
        }
        if (text.isEmpty()) {
            throw new GenerationException("the model returned content blocks with no text");
        }
        return text.toString();
    }

    private String baseUrl(String fallback) {
        String configured = properties.getGeneration().getApiBaseUrl();
        String base = configured == null || configured.isBlank() ? fallback : configured;
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * A request factory whose read timeout is the generation budget.
     *
     * <p>The connect timeout is deliberately much shorter than the read one. A model legitimately takes
     * many seconds to respond, but it should not take many seconds to <em>accept the connection</em> — a
     * provider whose endpoint is unreachable would otherwise hold a consumer thread for the full
     * generation budget before failing.
     */
    private static org.springframework.http.client.ClientHttpRequestFactory requestFactory(
            Duration timeout) {
        org.springframework.boot.http.client.HttpClientSettings settings =
                org.springframework.boot.http.client.HttpClientSettings.defaults()
                        .withTimeouts(Duration.ofSeconds(5), timeout);
        return org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder.detect()
                .build(settings);
    }

    /**
     * Retry policy.
     *
     * <p>Retries freely, which is safe because generation has no side effect beyond cost and the
     * investigation is persisted once regardless. Kept to a low attempt count because each retry doubles
     * the bill and the offline composer is an acceptable answer — spending three model calls to avoid a
     * deterministic report is the wrong trade.
     *
     * <p>Jittered backoff so a fleet of instances recovering from a provider outage does not synchronise
     * into retrying at the same instant and reproduce the overload.
     */
    private RetryConfig retryConfig() {
        return RetryConfig.custom()
                .maxAttempts(properties.getGeneration().getMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        Duration.ofMillis(400), 2.0, 0.3))
                .retryExceptions(GenerationException.class)
                .failAfterMaxAttempts(true)
                .build();
    }
}
