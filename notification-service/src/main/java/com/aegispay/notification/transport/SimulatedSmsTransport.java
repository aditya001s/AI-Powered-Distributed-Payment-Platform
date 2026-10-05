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
 * A simulated SMS transport.
 *
 * <p><strong>No SMS gateway is contacted.</strong> Every AegisPay message is billed to nobody and
 * delivered to nobody, which is stated here rather than left to be discovered.
 *
 * <p>What is real: the E.164 requirement, the segment arithmetic, and the treatment of an unroutable
 * number as permanent. Those matter more for SMS than for email because SMS costs money per segment and
 * per attempt — retrying an invalid number eight times is eight charges for a message that could never
 * arrive, which is the kind of bug that is only ever found on an invoice.
 */
@Component
public class SimulatedSmsTransport implements NotificationTransport {

    private static final Logger log = LoggerFactory.getLogger(SimulatedSmsTransport.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * E.164, and strictly.
     *
     * <p>Unlike an email address, a loose pattern here is dangerous rather than merely untidy: a
     * national-format number is ambiguous about country, and the wrong guess sends a stranger a message
     * about somebody else's payment.
     */
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,18}$");

    /** One GSM-7 segment. A longer message is split, and each part is billed separately. */
    private static final int SEGMENT_LENGTH = 160;

    private final NotificationProperties properties;

    public SimulatedSmsTransport(NotificationProperties properties) {
        this.properties = properties;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public boolean isAvailable() {
        return properties.getTransports().isSmsEnabled();
    }

    @Override
    public String send(String destination, RenderedMessage message) {
        if (destination == null || destination.isBlank()) {
            throw TransportException.permanent("NO_NUMBER",
                    "no phone number for this recipient");
        }
        if (!E164.matcher(destination).matches()) {
            throw TransportException.permanent("INVALID_NUMBER",
                    "not an E.164 number: " + ContactMasking.maskPhone(destination));
        }

        simulateLatency();
        simulateFailure();

        int segments = segmentsFor(message.body());
        String messageId = newMessageId();

        if (segments > 1) {
            // Worth a warning rather than silence. The renderer caps SMS at one segment, so more than
            // one means a template changed without anybody rechecking the length -- and the cost is
            // per segment, per recipient, forever.
            log.warn("""
                            SMS for a notification renders to {} segments, so it will be billed and \
                            delivered as {} messages. The renderer caps SMS at {} characters, so this \
                            means a template grew past it.""",
                    segments, segments, SEGMENT_LENGTH);
        }

        log.info("""
                        [SIMULATED SMS] to={} sender={} chars={} segments={} messageId={} \
                        -- no SMS gateway was contacted""",
                ContactMasking.maskPhone(destination),
                properties.getTransports().getSmsSenderId(),
                message.body().length(),
                segments,
                messageId);

        return messageId;
    }

    static int segmentsFor(String body) {
        if (body == null || body.isEmpty()) {
            return 0;
        }
        return (body.length() + SEGMENT_LENGTH - 1) / SEGMENT_LENGTH;
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
            throw TransportException.transient_("INTERRUPTED", "interrupted while sending");
        }
    }

    private void simulateFailure() {
        double rate = properties.getTransports().getSimulatedFailureRate();
        if (rate > 0.0 && ThreadLocalRandom.current().nextDouble() < rate) {
            throw TransportException.transient_("SIMULATED_GATEWAY_FAILURE",
                    "simulated transport failure (rate " + rate + ")");
        }
    }

    private static String newMessageId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return "sim-sms-" + HexFormat.of().formatHex(bytes);
    }
}
