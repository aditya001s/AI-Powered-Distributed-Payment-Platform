package com.aegispay.auth.bootstrap;

import com.aegispay.auth.config.AuthProperties;
import com.aegispay.auth.domain.ServiceClient;
import com.aegispay.auth.domain.UserAccount;
import com.aegispay.auth.repository.ServiceClientRepository;
import com.aegispay.auth.repository.UserAccountRepository;
import com.aegispay.common.security.Roles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reconciles credentials from configuration into the database at startup.
 *
 * <p><strong>Why not a Flyway migration.</strong> Seeding accounts in SQL means a
 * password hash — and in practice the plaintext it was generated from, in a code
 * comment or commit message — ends up in version control forever. Everything here
 * instead reads from the environment, so the repository contains no credential at
 * any point in its history.
 *
 * <p>Idempotent: safe to run on every boot and on every replica. Service-client
 * secrets are re-hashed each time, which is what makes rotation work — change the
 * environment variable, restart, and the new secret takes effect.
 */
@Component
public class CredentialSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CredentialSeeder.class);

    private final AuthProperties properties;
    private final UserAccountRepository userRepository;
    private final ServiceClientRepository clientRepository;
    private final PasswordEncoder passwordEncoder;
    private final String platformEnv;
    private final SecureRandom secureRandom = new SecureRandom();

    public CredentialSeeder(AuthProperties properties,
                            UserAccountRepository userRepository,
                            ServiceClientRepository clientRepository,
                            PasswordEncoder passwordEncoder,
                            @Value("${aegis.platform-env:local}") String platformEnv) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.clientRepository = clientRepository;
        this.passwordEncoder = passwordEncoder;
        this.platformEnv = platformEnv;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedServiceClients();
        seedDemoUsers();
    }

    /**
     * Ensures each configured machine client exists with the current secret.
     *
     * <p>Without at least one client, no service can obtain a token and every
     * {@code /internal/**} call across the platform fails with 401 — a confusing
     * cascade whose root cause is a missing environment variable. So a missing
     * configuration is reported loudly here instead.
     */
    @Transactional
    public void seedServiceClients() {
        if (properties.getServiceClients().isEmpty()) {
            log.error("""
                    No service clients are configured (aegis.auth.service-clients is empty). \
                    Service-to-service authentication will fail for every /internal/** endpoint. \
                    Set INTERNAL_CLIENT_ID and INTERNAL_CLIENT_SECRET.""");
            return;
        }

        for (AuthProperties.ServiceClient configured : properties.getServiceClients()) {
            if (configured.getClientSecret() == null || configured.getClientSecret().isBlank()) {
                log.error("Service client '{}' has a blank secret and was NOT seeded. "
                        + "Set the corresponding environment variable.", configured.getClientId());
                continue;
            }

            Set<String> scopes = new LinkedHashSet<>(configured.getScopes());
            Set<String> roles = new LinkedHashSet<>(configured.getRoles());
            String hash = passwordEncoder.encode(configured.getClientSecret());

            clientRepository.findByClientId(configured.getClientId())
                    .ifPresentOrElse(existing -> {
                        // Re-hash on every boot so rotating the environment variable
                        // is all it takes to rotate the credential.
                        existing.updateCredentials(hash, scopes, roles);
                        clientRepository.save(existing);
                        log.info("Reconciled service client '{}' (scopes={}, roles={})",
                                configured.getClientId(), scopes, roles);
                    }, () -> {
                        clientRepository.save(ServiceClient.of(
                                configured.getClientId(), hash, scopes, roles,
                                configured.getDescription()));
                        log.info("Created service client '{}' (scopes={}, roles={})",
                                configured.getClientId(), scopes, roles);
                    });
        }
    }

    /**
     * Creates the three demo accounts used by the README walkthrough and the
     * dashboard screenshots.
     *
     * <p>Guarded twice: it must be explicitly enabled, <em>and</em> it refuses to run
     * when {@code aegis.platform-env=kubernetes}. Seeding known accounts into a
     * production-like environment would create a standing set of credentials that
     * everyone who has read the README already knows.
     */
    @Transactional
    public void seedDemoUsers() {
        AuthProperties.Demo demo = properties.getDemo();
        if (!demo.isSeedEnabled()) {
            log.debug("Demo user seeding is disabled");
            return;
        }
        if ("kubernetes".equalsIgnoreCase(platformEnv)) {
            log.warn("Demo user seeding requested but refused: aegis.platform-env=kubernetes. "
                    + "Well-known demo credentials must never exist in a production-like environment.");
            return;
        }

        seedUser(demo.getUserEmail(), demo.getUserPassword(), "Asha Verma (Demo User)",
                "+919876543210", Set.of(Roles.USER));
        seedUser(demo.getMerchantEmail(), demo.getMerchantPassword(), "Chai Point (Demo Merchant)",
                "+919876543211", Set.of(Roles.USER, Roles.MERCHANT));
        seedUser(demo.getAdminEmail(), demo.getAdminPassword(), "Ops Admin (Demo)",
                "+919876543212", Set.of(Roles.USER, Roles.ADMIN, Roles.FRAUD_ANALYST));
    }

    private void seedUser(String email, String password, String fullName, String phone, Set<String> roles) {
        if (email == null || email.isBlank()) {
            return;
        }
        String normalised = UserAccount.normaliseEmail(email);

        if (userRepository.existsByEmail(normalised)) {
            log.debug("Demo user {} already exists; leaving it untouched", normalised);
            return;
        }
        if (password == null || password.isBlank()) {
            log.warn("Demo user {} not seeded: no password configured. "
                    + "Set the matching DEMO_*_PASSWORD variable.", normalised);
            return;
        }

        UserAccount account = UserAccount.register(
                generateUserId(), normalised, passwordEncoder.encode(password), fullName, phone, roles);
        userRepository.save(account);

        // The password is never logged, only the fact that the account was created.
        log.info("Seeded demo user {} [{}] with roles {}", account.getUserId(), normalised, roles);
    }

    private String generateUserId() {
        byte[] bytes = new byte[15];
        secureRandom.nextBytes(bytes);
        StringBuilder builder = new StringBuilder("USR-");
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString().toUpperCase();
    }
}
