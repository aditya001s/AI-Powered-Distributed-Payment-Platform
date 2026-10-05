package com.aegispay.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps the claims in an AegisPay access token onto Spring Security authorities.
 *
 * <p>Two claim shapes are read:
 * <ul>
 *   <li>{@code roles}: {@code ["USER","ADMIN"]} becomes
 *       {@code ROLE_USER}, {@code ROLE_ADMIN}.</li>
 *   <li>{@code scope}: the space-delimited OAuth2 scope string becomes
 *       {@code SCOPE_*} authorities, which is how {@code /internal/**} endpoints
 *       additionally require {@code SCOPE_internal}.</li>
 * </ul>
 *
 * <p>The principal name is the {@code sub} claim, which is the immutable user ID
 * rather than the email address. Emails change; authorisation decisions and audit
 * records must not silently follow.
 *
 * <p>Unknown role values are mapped through verbatim rather than filtered against
 * an allow-list. That is safe because the token is signed by our own issuer and
 * validated before this runs, so a role we do not recognise cannot have been
 * injected by a client, and it simply will not match any {@code @PreAuthorize}
 * expression.
 */
public class JwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_SCOPE = "scope";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_TOKEN_TYPE = "typ";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();

        List<String> roles = jwt.getClaimAsStringList(CLAIM_ROLES);
        if (roles != null) {
            roles.stream()
                    .filter(role -> role != null && !role.isBlank())
                    .map(role -> new SimpleGrantedAuthority(Roles.withPrefix(role.trim().toUpperCase())))
                    .forEach(authorities::add);
        }

        String scope = jwt.getClaimAsString(CLAIM_SCOPE);
        if (scope != null && !scope.isBlank()) {
            for (String single : scope.trim().split("\\s+")) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + single));
            }
        }

        // sub = immutable user ID (or client ID for machine tokens).
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
