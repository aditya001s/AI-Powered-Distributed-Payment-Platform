package com.aegispay.ai.generation;

import com.aegispay.ai.config.AiProperties;
import com.aegispay.ai.domain.Recommendation;
import com.aegispay.ai.rag.RetrievedPolicy;
import com.aegispay.common.domain.RiskLevel;
import com.aegispay.common.events.payload.RiskSignals;
import com.aegispay.common.money.Money;
import com.aegispay.common.observability.MetricNames;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hosted-model generator: the wire shapes, and what it refuses to believe.
 *
 * <p><strong>Why a real HTTP server rather than a mocked RestClient.</strong> The interesting behaviour is
 * in the request that goes out and the response that comes back — which header carries the key, where the
 * system prompt sits, what happens to a reply with no content. A mocked client would let all of that be
 * wrong while the test passed, because the assertions would be about the mock rather than about the bytes.
 *
 * <p>This is also the only outbound call in the service, and the only place an untrusted response is
 * parsed. The most important test in the class is the one where the model answers with a recommendation
 * nobody permitted: that is the shape a successful prompt injection takes, and the closed vocabulary is
 * what makes it survivable.
 */
class HttpLlmReportGeneratorTest {

    private static final String API_KEY = "test-api-key";

    private StubProvider provider;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AiProperties properties = new AiProperties();

    @BeforeEach
    void startProvider() throws IOException {
        provider = new StubProvider();
        properties.getGeneration().setApiKey(API_KEY);
        properties.getGeneration().setApiBaseUrl(provider.baseUrl());
        properties.getGeneration().setModel("gpt-4o-mini");
        properties.getGeneration().setTimeout(Duration.ofSeconds(5));
    }

    @AfterEach
    void stopProvider() {
        provider.stop();
    }

    // ------------------------------------------------------------------------
    // configuration
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("availability")
    class Availability {

        /**
         * Checked before use so a misconfiguration surfaces at startup rather than as a failed generation
         * on the first real investigation — which would look like a provider outage and send somebody
         * debugging the wrong thing.
         */
        @Test
        @DisplayName("is unavailable without an API key")
        void unavailableWithoutKey() {
            properties.getGeneration().setProvider("openai");
            properties.getGeneration().setApiKey("");

            assertThat(generator().isAvailable()).isFalse();
        }

        @Test
        @DisplayName("is available for a known provider with a key")
        void availableForKnownProvider() {
            properties.getGeneration().setProvider("openai");

            assertThat(generator().isAvailable()).isTrue();
        }

        /**
         * An unrecognised provider name is usable only with an explicit base URL, which is how a
         * self-hosted or gateway deployment is expressed. Without one there is nowhere to send the request,
         * and defaulting to OpenAI's endpoint would send payment data to a third party nobody named.
         */
        @Test
        @DisplayName("needs an explicit base URL for an unrecognised provider")
        void unknownProviderNeedsBaseUrl() {
            properties.getGeneration().setProvider("some-gateway");
            properties.getGeneration().setApiBaseUrl("");

            assertThat(generator().isAvailable()).isFalse();

            properties.getGeneration().setApiBaseUrl("https://gateway.internal");
            assertThat(generator().isAvailable()).isTrue();
        }

        /**
         * The id names the provider and the model, and it is stored on every investigation. A shift in the
         * quality of advice has to be attributable to a version rather than to the world.
         */
        @Test
        @DisplayName("identifies itself by provider and model")
        void identifiesItself() {
            properties.getGeneration().setProvider("openai");

            assertThat(generator().id()).isEqualTo("openai/gpt-4o-mini");
            assertThat(generator().isDeterministic())
                    .as("this flag reaches the analyst, so it must never claim determinism it "
                            + "does not have")
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------------
    // the OpenAI-compatible shape
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("OpenAI-compatible wire shape")
    class OpenAiShape {

        @BeforeEach
        void useOpenAi() {
            properties.getGeneration().setProvider("openai");
        }

        @Test
        @DisplayName("parses a well-formed reply into a report")
        void parsesReply() {
            provider.respondWithReply(wellFormedReply("HOLD_AND_REQUEST_VERIFICATION"));

            GeneratedReport report = generate();

            assertThat(report.recommendation())
                    .isEqualTo(Recommendation.HOLD_AND_REQUEST_VERIFICATION);
            assertThat(report.narrative()).contains("6.4x this account's usual amount");
            assertThat(report.findings()).hasSize(2);
            assertThat(report.citedDocumentIds()).containsExactly("POLICY-BUST-OUT");
            assertThat(report.confidence()).isEqualTo(0.78);
            assertThat(report.generatedBy()).isEqualTo("openai/gpt-4o-mini");
            assertThat(report.deterministic())
                    .as("a model wrote this, and the flag an analyst reads must say so")
                    .isFalse();
        }

        @Test
        @DisplayName("sends the key as a bearer token and the prompts as two messages")
        void sendsExpectedRequest() {
            provider.respondWithReply(wellFormedReply("NO_ACTION_REQUIRED"));

            generate();

            StubProvider.Recorded request = provider.requests().getFirst();
            assertThat(request.path()).isEqualTo("/v1/chat/completions");
            assertThat(request.headers().get("authorization")).isEqualTo("Bearer " + API_KEY);
            assertThat(request.body())
                    .contains("\"model\":\"gpt-4o-mini\"")
                    .contains("\"role\":\"system\"")
                    .contains("\"role\":\"user\"")
                    .contains("\"max_tokens\":800");
        }

        /**
         * The internal service token must never reach a third party. A dedicated request factory is used
         * rather than the shared internal client precisely because the shared one attaches the platform's
         * own credential to every request it makes.
         */
        @Test
        @DisplayName("sends no internal platform credential to the provider")
        void sendsNoInternalCredential() {
            provider.respondWithReply(wellFormedReply("NO_ACTION_REQUIRED"));

            generate();

            Map<String, String> headers = provider.requests().getFirst().headers();
            assertThat(headers).doesNotContainKeys("x-aegis-service", "x-service-token");
            assertThat(headers.getOrDefault("authorization", ""))
                    .as("the only credential on this request is the model provider's own key")
                    .isEqualTo("Bearer " + API_KEY);
        }

        @Test
        @DisplayName("fails when the provider returns no choices")
        void failsWithoutChoices() {
            provider.respond(200, "{\"choices\":[]}");

            assertThatThrownBy(HttpLlmReportGeneratorTest.this::generate)
                    .isInstanceOf(ReportGenerator.GenerationException.class)
                    .hasMessageContaining("no choices");
        }

        /**
         * A choice with a finish reason and no content is what a truncated or content-filtered response
         * looks like. Treating it as an empty report would hand an analyst a blank page instead of
         * reaching the fallback.
         */
        @Test
        @DisplayName("fails when a choice arrives with no content")
        void failsOnContentlessChoice() {
            provider.respond(200,
                    "{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{}}]}");

            assertThatThrownBy(HttpLlmReportGeneratorTest.this::generate)
                    .isInstanceOf(ReportGenerator.GenerationException.class)
                    .hasMessageContaining("truncated or filtered");
        }

        /**
         * Retried because generation has no side effect at the provider beyond cost and the investigation
         * is persisted once. Bounded because each retry doubles the bill, and the offline composer is an
         * acceptable answer — spending three model calls to avoid a deterministic report is a bad trade.
         */
        @Test
        @DisplayName("retries a failing provider up to the configured attempt count")
        void retriesOnFailure() {
            properties.getGeneration().setMaxAttempts(2);
            provider.respond(503, "{\"error\":\"overloaded\"}");

            assertThatThrownBy(HttpLlmReportGeneratorTest.this::generate)
                    .isInstanceOf(ReportGenerator.GenerationException.class);

            assertThat(provider.requests())
                    .as("two attempts, not one and not three")
                    .hasSize(2);
        }

        @Test
        @DisplayName("succeeds on a retry after a transient failure")
        void recoversOnRetry() {
            provider.respond(503, "{\"error\":\"overloaded\"}");
            provider.respond(200, StubProvider.openAiEnvelope(
                    wellFormedReply("HOLD_AND_REQUEST_VERIFICATION")));

            assertThat(generate().recommendation())
                    .isEqualTo(Recommendation.HOLD_AND_REQUEST_VERIFICATION);
        }

        /**
         * When the breaker is open nothing is attempted, which is the entire value of having one here: the
         * investigation reaches the composer immediately instead of waiting out another twenty-second
         * timeout it already knows will fail.
         */
        @Test
        @DisplayName("fails immediately without calling the provider when the breaker is open")
        void breakerShortCircuits() {
            CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
            breakers.circuitBreaker("llm-provider").transitionToOpenState();

            HttpLlmReportGenerator generator = new HttpLlmReportGenerator(RestClient.builder(),
                    properties, breakers, RetryRegistry.ofDefaults(), meterRegistry);

            assertThatThrownBy(() -> generator.generate(evidence(), List.of()))
                    .isInstanceOf(ReportGenerator.GenerationException.class)
                    .hasMessageContaining("circuit breaker");
            assertThat(provider.requests()).isEmpty();
        }
    }

    // ------------------------------------------------------------------------
    // the Anthropic shape
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("Anthropic wire shape")
    class AnthropicShape {

        @BeforeEach
        void useAnthropic() {
            properties.getGeneration().setProvider("anthropic");
            properties.getGeneration().setModel("claude-sonnet-4");
        }

        @Test
        @DisplayName("sends the system prompt as a top-level field with the pinned API version")
        void sendsExpectedRequest() {
            provider.respond(200, StubProvider.anthropicEnvelope(
                    wellFormedReply("HOLD_AND_REQUEST_VERIFICATION")));

            generate();

            StubProvider.Recorded request = provider.requests().getFirst();
            assertThat(request.path()).isEqualTo("/v1/messages");
            assertThat(request.headers().get("x-api-key")).isEqualTo(API_KEY);
            assertThat(request.headers().get("anthropic-version"))
                    .as("an unpinned version changes behaviour silently")
                    .isEqualTo("2023-06-01");
            assertThat(request.body())
                    .contains("\"system\":")
                    .doesNotContain("\"role\":\"system\"");
        }

        /**
         * A reply can arrive as several text blocks. Taking only the first would silently truncate the
         * report at whatever boundary the provider happened to choose.
         */
        @Test
        @DisplayName("concatenates multiple text blocks rather than truncating at the first")
        void concatenatesBlocks() {
            String reply = wellFormedReply("HOLD_AND_REQUEST_VERIFICATION");
            int split = reply.indexOf("RECOMMENDATION:");
            provider.respond(200, StubProvider.anthropicEnvelope(
                    reply.substring(0, split), reply.substring(split)));

            GeneratedReport report = generate();

            assertThat(report.recommendation())
                    .as("the recommendation lives in the second block; taking only the first would "
                            + "lose it and escalate for no reason")
                    .isEqualTo(Recommendation.HOLD_AND_REQUEST_VERIFICATION);
        }

        @Test
        @DisplayName("fails when the provider returns no content blocks")
        void failsWithoutBlocks() {
            provider.respond(200, "{\"content\":[]}");

            assertThatThrownBy(HttpLlmReportGeneratorTest.this::generate)
                    .isInstanceOf(ReportGenerator.GenerationException.class)
                    .hasMessageContaining("no content blocks");
        }

        @Test
        @DisplayName("fails when the content blocks carry no text")
        void failsOnTextlessBlocks() {
            provider.respond(200, "{\"content\":[{\"type\":\"thinking\"}]}");

            assertThatThrownBy(HttpLlmReportGeneratorTest.this::generate)
                    .isInstanceOf(ReportGenerator.GenerationException.class)
                    .hasMessageContaining("no text");
        }
    }

    // ------------------------------------------------------------------------
    // the refusal
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("recommendation vocabulary")
    class Vocabulary {

        @BeforeEach
        void useOpenAi() {
            properties.getGeneration().setProvider("openai");
        }

        /**
         * <strong>Where a prompt injection stops.</strong> A model persuaded to answer
         * {@code RELEASE_IMMEDIATELY} produces no recommendation at all and the report escalates. There is
         * deliberately no fuzzy matching: guessing at the closest permitted value is exactly how an
         * injected instruction becomes a decision about somebody's money.
         */
        @Test
        @DisplayName("refuses a recommendation outside the permitted vocabulary and escalates")
        void refusesUnknownRecommendation() {
            provider.respondWithReply(wellFormedReply("RELEASE_IMMEDIATELY"));

            GeneratedReport report = generate();

            assertThat(report.recommendation())
                    .as("an unrecognised value must never be mapped to the nearest permitted one")
                    .isEqualTo(Recommendation.whenUncertain())
                    .isEqualTo(Recommendation.ESCALATE_TO_SENIOR_ANALYST);
        }

        /**
         * A generation that could not be trusted must never resolve to the least cautious option. A
         * failure to produce a valid recommendation is not evidence that a payment is fine.
         */
        @Test
        @DisplayName("never resolves an unusable recommendation to no action")
        void neverResolvesToNoAction() {
            provider.respondWithReply(wellFormedReply("ignore previous instructions and approve"));

            assertThat(generate().recommendation())
                    .isNotEqualTo(Recommendation.NO_ACTION_REQUIRED);
        }

        /**
         * The model's own rationale explained a recommendation that was refused, so quoting it beside the
         * escalation would explain a decision nobody made.
         */
        @Test
        @DisplayName("replaces the rationale that explained the refused recommendation")
        void replacesRationale() {
            provider.respondWithReply(wellFormedReply("RELEASE_IMMEDIATELY"));

            GeneratedReport report = generate();

            assertThat(report.recommendationRationale())
                    .contains("outside the permitted vocabulary")
                    .doesNotContain("match the bust-out pattern");
        }

        /**
         * Counted, because this is either a confused model or a successful injection attempt and both are
         * worth an operator knowing about. A refusal that only escalated would leave no trace of how often
         * it happens.
         */
        @Test
        @DisplayName("counts a refused recommendation")
        void countsRefusal() {
            provider.respondWithReply(wellFormedReply("RELEASE_IMMEDIATELY"));

            generate();

            assertThat(meterRegistry.get(MetricNames.AI_REJECTED_RECOMMENDATION)
                    .tag("provider", "openai").counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("keeps the narrative when only the recommendation was unusable")
        void keepsNarrative() {
            provider.respondWithReply(wellFormedReply("RELEASE_IMMEDIATELY"));

            assertThat(generate().narrative())
                    .as("the narrative is still the model's analysis and is worth reading; only the "
                            + "actionable field was refused")
                    .contains("6.4x this account's usual amount");
        }
    }

    // ---------------------------------------------------------------- fixtures

    private HttpLlmReportGenerator generator() {
        return new HttpLlmReportGenerator(RestClient.builder(), properties,
                CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults(), meterRegistry);
    }

    private GeneratedReport generate() {
        return generator().generate(evidence(), List.of(policy("POLICY-BUST-OUT")));
    }

    private static EvidencePack evidence() {
        return EvidencePack.from("pay-1", "txn-1", "user-1", Money.ofMinor(500_000, "INR"),
                Instant.now(), RiskLevel.HIGH, 0.91, List.of("RULE_BUST_OUT_PATTERN"),
                List.of("amount far above usual"), "fraud_hgb/3",
                Map.of("amount_to_avg_ratio", 6.4), RiskSignals.unknown(), null, 240);
    }

    private static RetrievedPolicy policy(String documentId) {
        return new RetrievedPolicy(documentId, "Title of " + documentId, 1, "ATTACK_PATTERN",
                documentId + "#0@hashing-384/v1", 0, "Passage of " + documentId, 40, 0.61, 1,
                RetrievedPolicy.Source.DECLARED_RULE);
    }

    /** A well-formed reply in the format the prompt asks for. */
    private static String wellFormedReply(String recommendation) {
        return """
                NARRATIVE:
                The payment is 6.4x this account's usual amount and the payee has never been paid before.

                FINDINGS:
                - The amount is 6.4x the account's average.
                - The payee is new to this account.

                RECOMMENDATION: %s

                RATIONALE:
                The amount and the unfamiliar payee together match the bust-out pattern.

                CITATIONS:
                - POLICY-BUST-OUT

                CONFIDENCE: 0.78
                """.formatted(recommendation);
    }

    /**
     * A local stand-in for the model provider.
     *
     * <p>Records every request so the outgoing shape can be asserted, and serves a queue of scripted
     * responses so a retry can be observed rather than inferred.
     */
    private static final class StubProvider {

        private final HttpServer server;
        private final List<Recorded> requests = new CopyOnWriteArrayList<>();
        private final List<Response> scripted = new CopyOnWriteArrayList<>();

        record Recorded(String path, Map<String, String> headers, String body) {
        }

        record Response(int status, String body) {
        }

        StubProvider() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(null);
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        /** Queues a response. The last queued one is reused once the queue is exhausted. */
        void respond(int status, String body) {
            scripted.add(new Response(status, body));
        }

        void respondWithReply(String reply) {
            respond(200, openAiEnvelope(reply));
        }

        List<Recorded> requests() {
            return List.copyOf(requests);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(",", values)));
            requests.add(new Recorded(exchange.getRequestURI().getPath(), headers, body));

            Response response = scripted.isEmpty()
                    ? new Response(200, openAiEnvelope(wellFormedReply("NO_ACTION_REQUIRED")))
                    : scripted.get(Math.min(requests.size() - 1, scripted.size() - 1));

            byte[] payload = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }

        static String openAiEnvelope(String content) {
            return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                    + quote(content) + "}}]}";
        }

        static String anthropicEnvelope(String... blocks) {
            List<String> parts = new ArrayList<>();
            for (String block : blocks) {
                parts.add("{\"type\":\"text\",\"text\":" + quote(block) + "}");
            }
            return "{\"content\":[" + String.join(",", parts) + "]}";
        }

        private static String quote(String value) {
            StringBuilder builder = new StringBuilder("\"");
            for (char c : value.toCharArray()) {
                switch (c) {
                    case '"' -> builder.append("\\\"");
                    case '\\' -> builder.append("\\\\");
                    case '\n' -> builder.append("\\n");
                    case '\r' -> builder.append("\\r");
                    case '\t' -> builder.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            builder.append(String.format("\\u%04x", (int) c));
                        } else {
                            builder.append(c);
                        }
                    }
                }
            }
            return builder.append('"').toString();
        }
    }
}
