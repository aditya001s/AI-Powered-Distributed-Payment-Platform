package com.aegispay.notification.transport;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;
import com.aegispay.notification.config.NotificationProperties;
import com.aegispay.notification.template.RenderedMessage;
import com.aegispay.notification.util.ContactMasking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * A simulated email transport.
 *
 * <p><strong>No SMTP relay is contacted.</strong> The platform simulates its payment providers so that
 * failure and fallback can be exercised on demand, and email is simulated for the same reason: a
 * dead-letter queue that only fills during a real provider outage is a mechanism nobody has ever seen
 * work. {@code aegis.notification.transports.simulated-failure-rate} makes that path reachable
 * deliberately.
 *
 * <p>What is real: the address validation, the rejection of a malformed address as <em>permanent</em>
 * rather than retryable, the latency measurement, and the refusal to log a full address. Those are the
 * parts a real transport would still need, and they are on this side of the interface.
 *
 * <p>Swapping in a real transport means adding {@code spring-boot-starter-mail}, implementing
 * {@link NotificationTransport} against {@code JavaMailSender}, and mapping its exceptions onto the
 * retryable/permanent distinction. Nothing above this class changes.
 */
@Component
public class SimulatedEmailTransport implements NotificationTransport {

    private static final Logger log = LoggerFactory.getLogger(SimulatedEmailTransport.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Deliberately loose.
     *
     * <p>Rejecting addresses that look wrong but are valid is worse than accepting a few that will
     * bounce: a customer whose address this pattern refuses never receives anything and cannot fix it.
     * The checks that matter are the ones a transport genuinely cannot proceed past — no {@code @}, no
     * domain, whitespace, or a newline that would be header injection.
     */
    private static final Pattern PLAUSIBLE_EMAIL =
            Pattern.compile("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$");

    private final NotificationProperties properties;

    public SimulatedEmailTransport(NotificationProperties properties) {
        this.properties = properties;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public boolean isAvailable() {
        return properties.getTransports().isEmailEnabled();
    }

    @Override
    public String send(String destination, RenderedMessage message) {
        if (destination == null || destination.isBlank()) {
            throw TransportException.permanent("NO_ADDRESS",
                    "no email address for this recipient");
        }
        if (!PLAUSIBLE_EMAIL.matcher(destination).matches()) {
            // Permanent, so it is dead-lettered on the first attempt. Retrying a malformed address
            // eight times delays the record an operator needs and produces eight identical errors.
            throw TransportException.permanent("INVALID_ADDRESS",
                    "not a usable email address: " + ContactMasking.maskEmail(destination));
        }
        if (message.subject() == null || message.subject().isBlank()) {
            throw TransportException.permanent("NO_SUBJECT",
                    "an email needs a subject; every client files one without as spam");
        }

        simulateLatency();
        simulateFailure();

        String messageId = newMessageId();

        // The address is masked and the body is absent. This line exists so a local run shows the
        // message flowing, and a notification body contains a customer's payment amount and a note
        // written by another customer -- neither belongs in an application log.
        log.info("""
                        [SIMULATED EMAIL] to={} from={} <{}> subject='{}' bodyChars={} messageId={} \
                        -- no SMTP relay was contacted""",
                ContactMasking.maskEmail(destination),
                properties.getTransports().getEmailFromName(),
                properties.getTransports().getEmailFromAddress(),
                message.subject(),
                message.body().length(),
                messageId);

        return messageId;
    }

    private void simulateLatency() {
        long millis = properties.getTransports().getSimulatedLatency().toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            // Retryable: the message was not sent, and the interruption says nothing about whether it
            // could be. Losing the interrupt flag here would leave a shutting-down worker looping.
            throw TransportException.transient_("INTERRUPTED",
                    "interrupted while sending");
        }
    }

    private void simulateFailure() {
        double rate = properties.getTransports().getSimulatedFailureRate();
        if (rate > 0.0 && ThreadLocalRandom.current().nextDouble() < rate) {
            throw TransportException.transient_("SIMULATED_SMTP_FAILURE",
                    "simulated transport failure (rate " + rate + ")");
        }
    }

    /**
     * A reference that cannot be mistaken for a real one.
     *
     * <p>Prefixed {@code sim-} on purpose. A reference shaped like a genuine provider message id would
     * eventually be pasted into a support ticket and looked up somewhere it does not exist.
     */
    private static String newMessageId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return "sim-email-" + HexFormat.of().formatHex(bytes);
    }
}
