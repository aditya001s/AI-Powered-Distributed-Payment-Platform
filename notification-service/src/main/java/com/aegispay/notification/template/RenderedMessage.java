package com.aegispay.notification.template;

import com.aegispay.common.domain.SharedEnums.NotificationChannel;

/**
 * One message, ready to hand to a transport.
 *
 * @param subject null for channels that have none. SMS and in-app do not.
 * @param body    the final text. Already interpolated, sanitised and length-capped, so a transport
 *                does no further processing — a transport that reformatted its input would be a
 *                second place where escaping could be got wrong.
 */
public record RenderedMessage(NotificationChannel channel, String subject, String body) {

    public RenderedMessage {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException(
                    "a rendered message must have a body; an empty one would be recorded as a "
                            + "successful delivery of nothing");
        }
    }
}
