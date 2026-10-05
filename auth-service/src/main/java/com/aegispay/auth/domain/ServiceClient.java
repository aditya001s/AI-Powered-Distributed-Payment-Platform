package com.aegispay.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A machine identity permitted to use the OAuth2 client-credentials grant.
 *
 * <p>Rows are reconciled from configuration at startup (see
 * {@code ServiceClientSeeder}) rather than inserted by a migration, because a
 * migration file containing a credential would be committed to git. The secret
 * arrives from the environment and only its BCrypt hash is stored — the same
 * treatment as a user password, since that is exactly what it is.
 *
 * <p>Scopes and roles are stored as comma-separated strings. A join table would be
 * over-engineering for a handful of static internal clients, and this keeps the
 * seeding logic a single upsert.
 */
@Entity
@Table(name = "service_clients", indexes = {
        @Index(name = "idx_service_clients_client_id", columnList = "client_id", unique = true)
})
public class ServiceClient {

    @Id
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, unique = true, length = 64)
    private String clientId;

    /** BCrypt hash of the client secret. */
    @Column(name = "client_secret_hash", nullable = false, length = 120)
    private String clientSecretHash;

    /** Comma-separated scopes this client may request, e.g. {@code internal}. */
    @Column(name = "scopes", nullable = false, length = 256)
    private String scopes;

    /** Comma-separated roles embedded in issued tokens, e.g. {@code SERVICE}. */
    @Column(name = "roles", nullable = false, length = 256)
    private String roles;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "description", length = 256)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** Last successful token issuance, so a dormant credential is easy to spot. */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected ServiceClient() {
        // JPA
    }

    public static ServiceClient of(String clientId, String clientSecretHash,
                                   Set<String> scopes, Set<String> roles, String description) {
        ServiceClient client = new ServiceClient();
        client.clientId = clientId;
        client.clientSecretHash = clientSecretHash;
        client.scopes = String.join(",", scopes);
        client.roles = String.join(",", roles);
        client.description = description;
        client.enabled = true;
        client.createdAt = Instant.now();
        client.updatedAt = client.createdAt;
        return client;
    }

    /** Applies a rotated secret or changed permission set from configuration. */
    public void updateCredentials(String clientSecretHash, Set<String> scopes, Set<String> roles) {
        this.clientSecretHash = clientSecretHash;
        this.scopes = String.join(",", scopes);
        this.roles = String.join(",", roles);
        this.updatedAt = Instant.now();
    }

    public void recordUse() {
        this.lastUsedAt = Instant.now();
    }

    public Set<String> scopeSet() {
        return split(scopes);
    }

    public Set<String> roleSet() {
        return split(roles);
    }

    /**
     * True when every requested scope was granted to this client.
     *
     * <p>An empty request means "give me my default scopes"; a request for a scope
     * the client does not hold is refused rather than silently narrowed, so a
     * misconfigured caller fails visibly instead of getting a token that will be
     * rejected later for reasons that are hard to trace.
     */
    public boolean grantsAll(Set<String> requestedScopes) {
        return requestedScopes.isEmpty() || scopeSet().containsAll(requestedScopes);
    }

    private Set<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public Long getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecretHash() {
        return clientSecretHash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = Instant.now();
    }

    public String getDescription() {
        return description;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    @Override
    public String toString() {
        return "ServiceClient{clientId='" + clientId + "', enabled=" + enabled + '}';
    }
}
