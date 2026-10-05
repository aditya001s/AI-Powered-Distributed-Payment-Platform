package com.aegispay.auth.service;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The client context of one HTTP request: source IP and user agent.
 *
 * <p>Used for audit records, session listing and as a fraud signal.
 */
public record RequestContext(String ipAddress, String userAgent) {

    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_REAL_IP = "X-Real-IP";

    public static RequestContext empty() {
        return new RequestContext(null, null);
    }

    /**
     * Extracts the client context from a servlet request.
     *
     * <p><strong>On trusting {@code X-Forwarded-For}.</strong> Every request here
     * arrives through the API gateway, so {@code getRemoteAddr()} would always
     * return the gateway's own address and be useless. The forwarded header is
     * therefore honoured, but only the <em>first</em> entry is taken and only after
     * validating its shape.
     *
     * <p>This is safe <em>only</em> because the header is overwritten by the trusted
     * ingress on the way in, so a client-supplied value never survives. Trusting
     * this header on a directly-exposed service would let any caller forge their
     * source IP and defeat IP-based rate limiting and fraud rules. The trust
     * boundary is the ingress, and that is enforced in the gateway configuration,
     * not here.
     */
    public static RequestContext from(HttpServletRequest request) {
        if (request == null) {
            return empty();
        }
        return new RequestContext(resolveClientIp(request), truncate(request.getHeader("User-Agent"), 256));
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader(HEADER_FORWARDED_FOR);
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // "client, proxy1, proxy2" -> the original client is first.
            String candidate = forwardedFor.split(",")[0].trim();
            if (isPlausibleIp(candidate)) {
                return candidate;
            }
        }
        String realIp = request.getHeader(HEADER_REAL_IP);
        if (realIp != null && isPlausibleIp(realIp.trim())) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Cheap shape check. Not full IP validation: the goal is to reject values that
     * would corrupt logs or be stored as an oversized string, not to prove the
     * address is routable.
     */
    private static boolean isPlausibleIp(String value) {
        if (value == null || value.isBlank() || value.length() > 45) {
            return false;
        }
        return value.matches("^[0-9a-fA-F:.\\[\\]]+$");
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
