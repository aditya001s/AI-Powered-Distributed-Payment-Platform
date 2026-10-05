package com.aegispay.notification.service;

import com.aegispay.notification.config.NotificationProperties;
import com.aegispay.notification.domain.NotificationPreferences;
import com.aegispay.notification.repository.NotificationPreferencesRepository;
import com.aegispay.notification.util.ContactMasking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds where to send a notification.
 *
 * <h2>Two sources, on purpose</h2>
 *
 * <p><strong>The email address comes from auth-service</strong>, resolved per notification rather than
 * copied into this database. It is an identity attribute: the customer registered with it and it is
 * verified at signup, so auth-service is its source of truth and a changed address should take effect on
 * the next notification rather than whenever somebody remembers to synchronise.
 *
 * <p><strong>The phone number comes from this service's own preferences.</strong> Nobody logs in with a
 * phone number here; it exists solely so an SMS can be sent, and sending one requires the customer to
 * have asked. Keeping the number beside the consent that justifies it means the two cannot become
 * separated, and it keeps a phone number out of the service that every other service queries for
 * identity — auth-service's internal view deliberately omits it for exactly that reason.
 *
 * <h2>Why the lookup is cached, and what that costs</h2>
 *
 * <p>A completed payment produces two notifications, each fanning out to two channels, so one payment can
 * ask for the same address several times within a second. Caching also means a brief auth-service outage
 * does not stop every notification on the platform.
 *
 * <p>The cost is honest and bounded: a customer who changes their email keeps receiving notifications at
 * the old address until the entry expires. Ten minutes puts that window inside one support call rather
 * than one billing cycle.
 */
@Service
public class RecipientResolver {

    private static final Logger log = LoggerFactory.getLogger(RecipientResolver.class);

    private final RestClient client;
    private final String authBaseUrl;
    private final NotificationPreferencesRepository preferences;
    private final NotificationProperties properties;

    /**
     * A deliberately small cache.
     *
     * <p>A {@code ConcurrentHashMap} with timestamps rather than a caching library. The access pattern is
     * a handful of reads per payment with a fixed TTL and no need for statistics, weighting or refresh —
     * and adding a dependency to hold ten thousand strings would be a larger surface than the problem.
     */
    private final Map<String, CachedContact> cache = new ConcurrentHashMap<>();

    private record CachedContact(String email, String fullName, Instant expiresAt) {

        boolean isFresh() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    /** Where to send, and what to call the person. */
    public record Recipient(String userId, String email, String fullName, String phoneE164,
                            boolean smsOptedIn) {

        /**
         * A complete greeting line: {@code "Hi Alice,"} or {@code "Hi,"}.
         *
         * <p><strong>Fully formed rather than just the name.</strong> The first attempt returned
         * {@code " Alice"} with a leading space, so a template could write {@code Hi{{greetingName?}},} and
         * degrade to "Hi," when no name was known. That broke: every interpolated value is stripped as part
         * of sanitising it, so the space vanished and every email opened "HiAlice,".
         *
         * <p>The lesson is more general than the bug. A value that carries its own significant whitespace is
         * fragile, because sanitising untrusted input necessarily normalises whitespace and cannot tell
         * which spaces were meaningful. Composing the whole line here removes the ambiguity: the template
         * interpolates one value and does no spacing arithmetic.
         *
         * <p>First name only. "Hi Alice" reads as a message from a company that knows you;
         * "Hi Alice Beatrice Chandra-Smith" reads as a mail merge.
         */
        public String greeting() {
            if (fullName == null || fullName.isBlank()) {
                // "Hi," rather than "Hi there," -- a generic friendly placeholder is worse than a plain
                // opening when the platform genuinely does not know the name.
                return "Hi,";
            }
            return "Hi " + fullName.strip().split("\\s+")[0] + ",";
        }
    }

    public RecipientResolver(RestClient.Builder builder,
                             @Qualifier("internalRestClient") RestClient internalRestClient,
                             @Value("${aegis.clients.auth-service-url:http://localhost:8081}")
                             String authBaseUrl,
                             NotificationPreferencesRepository preferences,
                             NotificationProperties properties) {
        this.authBaseUrl = trimTrailingSlash(authBaseUrl);
        this.preferences = preferences;
        this.properties = properties;

        // The shared internal client carries the service token this call needs, but its ten-second read
        // timeout is far too long for a lookup sitting between a Kafka record and a durable write.
        // mutate() keeps the auth interceptor and replaces only the timeouts.
        Duration timeout = properties.getRecipients().getLookupTimeout();
        this.client = internalRestClient.mutate()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(HttpClientSettings.defaults()
                                .withTimeouts(Duration.ofSeconds(1), timeout)))
                .build();
    }

    /**
     * Resolves everything needed to deliver to a user.
     *
     * @param suppliedEmail an address on the event, if the producer already knew one. Trusted in
     *                      preference to a lookup: a producer that took the trouble to include one may
     *                      be notifying an address that is not the account's, and overriding it with the
     *                      account address would send the message to the wrong person.
     */
    @Transactional(readOnly = true)
    public Recipient resolve(String userId, String suppliedEmail, String suppliedPhone) {
        Optional<NotificationPreferences> stored = preferences.findByUserId(userId);

        String phone = suppliedPhone != null && !suppliedPhone.isBlank()
                ? suppliedPhone
                : stored.map(NotificationPreferences::getPhoneE164).orElse(null);

        // A supplied phone number does not imply consent -- consent is a fact about the account, not
        // about the event -- so opt-in is always read from the stored preferences.
        boolean optedIn = stored.map(NotificationPreferences::isSmsEnabled).orElse(false);

        if (suppliedEmail != null && !suppliedEmail.isBlank()) {
            return new Recipient(userId, suppliedEmail, null, phone, optedIn);
        }

        CachedContact contact = lookup(userId);
        return new Recipient(userId, contact == null ? null : contact.email(),
                contact == null ? null : contact.fullName(), phone, optedIn);
    }

    /** The user's preferences, or the defaults for somebody who has never set any. */
    @Transactional(readOnly = true)
    public NotificationPreferences preferencesFor(String userId) {
        return preferences.findByUserId(userId)
                .orElseGet(() -> NotificationPreferences.defaultsFor(userId));
    }

    // --------------------------------------------------------------- internals

    private CachedContact lookup(String userId) {
        CachedContact cached = cache.get(userId);
        if (cached != null && cached.isFresh()) {
            return cached;
        }

        try {
            InternalUserView view = client.get()
                    .uri(authBaseUrl + "/internal/users/{userId}", userId)
                    .retrieve()
                    .body(InternalUserView.class);

            if (view == null || view.email() == null || view.email().isBlank()) {
                log.warn("auth-service returned no email address for user {}; the email channel will "
                        + "be suppressed for this notification", userId);
                return null;
            }
            CachedContact contact = new CachedContact(view.email(), view.fullName(),
                    Instant.now().plus(properties.getRecipients().getCacheTtl()));
            put(userId, contact);
            return contact;

        } catch (RuntimeException ex) {
            // Not rethrown. A failed lookup must not fail the notification: the record still needs to
            // exist so the retry worker can try again once auth-service is back. Rethrowing would push
            // the event into a Kafka retry, and after four attempts into a dead-letter topic that
            // nothing drains -- turning a brief identity-service blip into permanently lost
            // notifications.
            log.warn("Could not resolve a delivery address for user {} ({}); the email channel will "
                    + "be suppressed and can be replayed once the lookup works", userId,
                    ex.getMessage());
            return null;
        }
    }

    private void put(String userId, CachedContact contact) {
        int max = properties.getRecipients().getCacheMaxEntries();
        if (max > 0 && cache.size() >= max) {
            // Cleared wholesale rather than evicted by age. Tracking access order would need a second
            // structure and a lock, and the consequence of clearing is a brief run of cache misses
            // against an endpoint that is already fast -- which is a much smaller cost than the
            // concurrency bug an eviction policy would invite.
            log.debug("Recipient cache reached {} entries; clearing", max);
            cache.clear();
        }
        cache.put(userId, contact);
    }

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * The subset of auth-service's internal identity view this service reads.
     *
     * <p>Only two fields, and declared locally rather than importing auth-service's DTO — this service
     * must not depend on that module's classpath, and the coupling between them is an HTTP contract.
     * Unknown properties are ignored, so auth-service can add fields without redeploying this one.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record InternalUserView(String userId, String email, String fullName, boolean active) {
    }

    /** Clears the cache. For tests and for an operator who has just corrected an address. */
    public void invalidate(String userId) {
        if (userId == null) {
            cache.clear();
        } else {
            cache.remove(userId);
        }
    }

    /** Masked, for the admin view: enough to confirm an address without listing every customer's. */
    public String maskedEmailFor(String userId) {
        CachedContact cached = cache.get(userId);
        return cached == null ? "(not cached)" : ContactMasking.maskEmail(cached.email());
    }
}
