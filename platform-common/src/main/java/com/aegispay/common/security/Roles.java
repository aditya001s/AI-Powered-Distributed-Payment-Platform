package com.aegispay.common.security;

/**
 * The platform's authorities.
 *
 * <p>Spring Security compares authority strings literally, so a typo in a
 * {@code @PreAuthorize} expression fails <em>open</em> in the worst case (the
 * expression never matches, or matches something unintended). Constants make that
 * a compile-time concern.
 *
 * <p>Note the {@code ROLE_} prefix convention: {@code hasRole('ADMIN')} adds the
 * prefix implicitly, while {@code hasAuthority(...)} does not. Both spellings are
 * provided so either style is correct.
 */
public final class Roles {

    /** An end customer. Can transact only on wallets they own. */
    public static final String USER = "USER";

    /** A merchant. Receives payments and reads its own settlement data. */
    public static final String MERCHANT = "MERCHANT";

    /**
     * A fraud analyst. Can read any transaction and any investigation, and can
     * release or reject a held payment. Cannot change balances directly.
     */
    public static final String FRAUD_ANALYST = "FRAUD_ANALYST";

    /** Platform administrator. Full read access plus operational actions. */
    public static final String ADMIN = "ADMIN";

    /**
     * Machine identity for service-to-service calls, obtained via the OAuth2
     * client-credentials grant. Required by every {@code /internal/**} endpoint,
     * and never issued to a human.
     */
    public static final String SERVICE = "SERVICE";

    public static final String ROLE_USER = "ROLE_" + USER;
    public static final String ROLE_MERCHANT = "ROLE_" + MERCHANT;
    public static final String ROLE_FRAUD_ANALYST = "ROLE_" + FRAUD_ANALYST;
    public static final String ROLE_ADMIN = "ROLE_" + ADMIN;
    public static final String ROLE_SERVICE = "ROLE_" + SERVICE;

    /** Scope granted to client-credentials tokens, checked alongside the role. */
    public static final String SCOPE_INTERNAL = "internal";

    private Roles() {
    }

    public static String withPrefix(String role) {
        return role.startsWith("ROLE_") ? role : "ROLE_" + role;
    }
}
