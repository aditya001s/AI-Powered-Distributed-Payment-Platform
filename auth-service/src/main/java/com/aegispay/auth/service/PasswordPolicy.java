package com.aegispay.auth.service;

import com.aegispay.auth.config.AuthProperties;
import com.aegispay.common.api.ErrorCode;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Enforces password strength rules.
 *
 * <p>Length is weighted most heavily because it contributes far more entropy than
 * character-class rules do. Composition requirements are still applied since they
 * are widely expected, but the floor is 12 characters rather than the 8 that
 * character rules are usually paired with.
 *
 * <p>A small deny-list of well-known passwords is checked too. Complexity rules
 * alone happily accept {@code Password123!}, which appears in every breach corpus
 * and is therefore effectively already compromised.
 */
@Component
public class PasswordPolicy {

    /**
     * The most common passwords that pass typical complexity checks. A production
     * deployment would check against a full breached-password corpus (for example
     * the Have I Been Pwned k-anonymity API); this is the same idea at a scale
     * appropriate for a self-contained project.
     */
    private static final Set<String> DENY_LIST = Set.of(
            "password", "password1", "password123", "password123!", "passw0rd",
            "qwerty123", "qwertyuiop", "welcome123", "admin123", "letmein123",
            "iloveyou123", "123456789012", "abc123456789", "aegispay123",
            "changeme123", "secret123", "monkey123", "dragon123", "football123");

    private final AuthProperties.Password config;

    public PasswordPolicy(AuthProperties properties) {
        this.config = properties.getPassword();
    }

    /**
     * Validates a candidate password.
     *
     * <p>Reports <em>all</em> violations at once rather than the first one found, so
     * a user is not forced through several round trips to discover the rules.
     *
     * @throws BadRequestException when the password does not meet policy
     */
    public void validate(String password) {
        List<String> violations = collectViolations(password);
        if (!violations.isEmpty()) {
            throw new BadRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "Password does not meet the security policy: " + String.join("; ", violations));
        }
    }

    private List<String> collectViolations(String password) {
        List<String> violations = new ArrayList<>();

        if (password == null || password.isBlank()) {
            violations.add("password is required");
            return violations;
        }
        if (password.length() < config.getMinLength()) {
            violations.add("must be at least " + config.getMinLength() + " characters");
        }
        // BCrypt truncates beyond 72 UTF-8 bytes, not 72 Java characters. A
        // multibyte password can be under the DTO character cap and still lose
        // entropy silently unless the encoded length is checked explicitly.
        int maxBytes = Math.min(config.getMaxLength(), 72);
        if (password.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            violations.add("must be at most " + maxBytes + " UTF-8 bytes for BCrypt");
        }
        if (config.isRequireMixedCase()) {
            if (password.chars().noneMatch(Character::isUpperCase)) {
                violations.add("must contain an uppercase letter");
            }
            if (password.chars().noneMatch(Character::isLowerCase)) {
                violations.add("must contain a lowercase letter");
            }
        }
        if (config.isRequireDigit() && password.chars().noneMatch(Character::isDigit)) {
            violations.add("must contain a digit");
        }
        if (config.isRequireSpecial()
                && password.chars().noneMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c))) {
            violations.add("must contain a special character");
        }
        if (isDenyListed(password)) {
            violations.add("is a commonly used password and is not permitted");
        }
        if (isSingleRepeatedCharacter(password)) {
            violations.add("must not be a single repeated character");
        }
        if (containsLongSequentialRun(password)) {
            violations.add("must not contain a long sequential run such as 'abcdef' or '123456'");
        }
        return violations;
    }

    /**
     * Checks the password against the deny list, allowing for trivial decoration.
     *
     * <p>An exact match is not enough. Faced with a "must contain a special
     * character" rule, users overwhelmingly take a weak base password and append one
     * symbol: {@code password} becomes {@code Password123!}. Those decorated variants
     * are just as present in breach corpora as the base, so the comparison is made
     * against three forms:
     *
     * <ol>
     *   <li>the password lowercased,</li>
     *   <li>with trailing non-alphanumeric decoration removed
     *       ({@code aegispay123!} &rarr; {@code aegispay123}),</li>
     *   <li>with all non-alphanumerics removed
     *       ({@code p@ssword-123} &rarr; {@code pssword123}).</li>
     * </ol>
     *
     * <p>This is a deliberately small version of what a production deployment would
     * do with a full breached-password corpus (for example the Have I Been Pwned
     * k-anonymity API), which would supersede this list rather than extend it.
     */
    private boolean isDenyListed(String password) {
        String lower = password.toLowerCase();
        if (DENY_LIST.contains(lower)) {
            return true;
        }
        String withoutTrailingDecoration = lower.replaceAll("[^a-z0-9]+$", "");
        if (!withoutTrailingDecoration.isEmpty() && DENY_LIST.contains(withoutTrailingDecoration)) {
            return true;
        }
        String alphanumericOnly = lower.replaceAll("[^a-z0-9]", "");
        return !alphanumericOnly.isEmpty() && DENY_LIST.contains(alphanumericOnly);
    }

    private boolean isSingleRepeatedCharacter(String password) {
        return password.chars().distinct().count() == 1L;
    }

    /** Detects runs of six or more consecutive ascending or descending characters. */
    private boolean containsLongSequentialRun(String password) {
        String lower = password.toLowerCase();
        int ascending = 1;
        int descending = 1;
        for (int i = 1; i < lower.length(); i++) {
            int delta = lower.charAt(i) - lower.charAt(i - 1);
            ascending = delta == 1 ? ascending + 1 : 1;
            descending = delta == -1 ? descending + 1 : 1;
            if (ascending >= 6 || descending >= 6) {
                return true;
            }
        }
        return false;
    }
}
