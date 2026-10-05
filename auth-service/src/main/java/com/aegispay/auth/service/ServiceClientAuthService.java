package com.aegispay.auth.service;

import com.aegispay.auth.api.AuthDtos.ServiceTokenResponse;
import com.aegispay.auth.domain.AuditLog;
import com.aegispay.auth.domain.ServiceClient;
import com.aegispay.auth.repository.ServiceClientRepository;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The OAuth2 client-credentials grant, used only for service-to-service calls.
 *
 * <p>The flow is intentionally the same shape as user login: look up the client,
 * verify a BCrypt-hashed secret, issue a short-lived signed token. Reusing one
 * validation path for both humans and machines means there is no second, less
 * scrutinised authentication mechanism — a static shared API key would have been
 * exactly that.
 */
@Service
public class ServiceClientAuthService {

    private static final Logger log = LoggerFactory.getLogger(ServiceClientAuthService.class);

    private final ServiceClientRepository clientRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final AuditService auditService;

    public ServiceClientAuthService(ServiceClientRepository clientRepository,
                                    PasswordEncoder passwordEncoder,
                                    TokenIssuer tokenIssuer,
                                    AuditService auditService) {
        this.clientRepository = clientRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.auditService = auditService;
    }

    /**
     * Issues a machine token.
     *
     * @param clientId        the client identifier
     * @param clientSecret    the plaintext secret, compared against a stored hash
     * @param requestedScopes scopes to embed; empty means "all scopes this client holds"
     */
    @Transactional
    public ServiceTokenResponse issueToken(String clientId, String clientSecret,
                                           Set<String> requestedScopes, RequestContext context) {

        var maybeClient = clientRepository.findByClientId(clientId);

        if (maybeClient.isEmpty()) {
            // Same dummy-hash timing equalisation as the user login path, so an
            // unknown client ID and a wrong secret take comparable time.
            passwordEncoder.matches(clientSecret, dummyHash());
            throw deny(clientId, "unknown client", context);
        }

        ServiceClient client = maybeClient.get();

        if (!client.isEnabled()) {
            throw deny(clientId, "client is disabled", context);
        }

        if (!passwordEncoder.matches(clientSecret, client.getClientSecretHash())) {
            throw deny(clientId, "invalid client secret", context);
        }

        Set<String> granted = resolveScopes(client, requestedScopes, context);

        client.recordUse();
        clientRepository.save(client);

        var token = tokenIssuer.issueServiceToken(client, granted);

        log.debug("Issued service token to client {} with scopes {}", clientId, granted);
        auditService.record(AuditLog.EventType.SERVICE_TOKEN_ISSUED, AuditLog.Outcome.SUCCESS,
                clientId, null, "Service token issued", context,
                Map.of("scopes", String.join(" ", granted)));

        return ServiceTokenResponse.of(token.token(), token.expiresInSeconds(), String.join(" ", granted));
    }

    /**
     * Resolves the scopes to grant.
     *
     * <p>An over-broad request is <em>refused</em> rather than silently narrowed to
     * the intersection. Silent narrowing produces a token that looks fine but fails
     * later at whichever endpoint needed the missing scope, and that failure is very
     * hard to trace back to the token request. Failing here names the problem.
     */
    private Set<String> resolveScopes(ServiceClient client, Set<String> requested, RequestContext context) {
        if (requested == null || requested.isEmpty()) {
            return client.scopeSet();
        }
        if (!client.grantsAll(requested)) {
            Set<String> missing = new LinkedHashSet<>(requested);
            missing.removeAll(client.scopeSet());
            log.warn("Client {} requested scopes it does not hold: {}", client.getClientId(), missing);
            auditService.record(AuditLog.EventType.SERVICE_TOKEN_DENIED, AuditLog.Outcome.FAILURE,
                    client.getClientId(), null, "Requested unauthorised scopes", context,
                    Map.of("requested", String.join(" ", requested),
                            "missing", String.join(" ", missing)));
            throw new UnauthorizedException(ErrorCode.ACCESS_DENIED,
                    "The client is not authorised for the requested scopes");
        }
        return requested;
    }

    /**
     * Audits the denial and returns the exception for the caller to throw.
     *
     * <p>Returning rather than throwing keeps the control flow visible at the call
     * site ({@code throw deny(...)}), so the compiler knows execution stops there.
     * The client-facing reason is always the same regardless of which check failed,
     * so this cannot be used to probe which client IDs exist.
     */
    private UnauthorizedException deny(String clientId, String reason, RequestContext context) {
        log.warn("Service token denied for client '{}': {}", clientId, reason);
        auditService.record(AuditLog.EventType.SERVICE_TOKEN_DENIED, AuditLog.Outcome.FAILURE,
                clientId, null, "Service token denied: " + reason, context,
                Map.of("reason", reason));
        return new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS,
                "Client authentication failed");
    }

    private String dummyHash() {
        return "{bcrypt}$2a$12$AbCdEfGhIjKlMnOpQrStUuVwXyZ0123456789abcdefghijklmnopq";
    }
}
