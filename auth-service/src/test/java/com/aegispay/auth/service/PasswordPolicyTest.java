package com.aegispay.auth.service;

import com.aegispay.auth.config.AuthProperties;
import com.aegispay.common.exception.DomainExceptions.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for {@link PasswordPolicy}.
 *
 * <p>Runs with no Spring context: the policy is a plain object taking configuration
 * as a constructor argument, which is what makes it testable in milliseconds. That
 * is the practical payoff of keeping rules out of controllers and framework classes.
 */
class PasswordPolicyTest {

    private PasswordPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new PasswordPolicy(new AuthProperties());
    }

    @ParameterizedTest
    @DisplayName("accepts passwords that satisfy the policy")
    @ValueSource(strings = {
            "correct-horse-Battery-9!",
            "Tr0ubled#Waters2026",
            "myVery$ecureP4ss",
            "A1b2C3d4E5f6!@#"
    })
    void acceptsStrongPasswords(String password) {
        assertThatCode(() -> policy.validate(password)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a password shorter than the minimum length")
    void rejectsShortPassword() {
        // Length dominates character-class rules for real entropy, which is why the
        // floor is 12 rather than the 8 that complexity rules are usually paired with.
        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> policy.validate("Short1!x"))
                .withMessageContaining("at least 12 characters");
    }

    /**
     * BCrypt silently truncates input beyond 72 bytes. Accepting a 200-character
     * password would give the user a false sense of strength while only the first 72
     * bytes were ever hashed, so an explicit maximum is the honest behaviour.
     */
    @Test
    @DisplayName("rejects a password longer than BCrypt actually hashes")
    void rejectsOverLongPassword() {
        String tooLong = "Aa1!" + "x".repeat(80);
        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> policy.validate(tooLong))
                .withMessageContaining("at most 72 UTF-8 bytes");
    }

    @Test
    @DisplayName("rejects a multibyte password whose UTF-8 encoding exceeds BCrypt's limit")
    void rejectsMultibytePasswordBeyondByteLimit() {
        // 30 CJK characters are 90 bytes although the Java string is under 72 characters.
        String tooManyBytes = "Aa1!" + "界".repeat(30);

        assertThat(tooManyBytes.length()).isLessThan(72);
        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> policy.validate(tooManyBytes))
                .withMessageContaining("at most 72 UTF-8 bytes");
    }

    @Test
    @DisplayName("requires mixed case, a digit and a special character")
    void requiresCharacterClasses() {
        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> policy.validate("alllowercase1!"))
                .withMessageContaining("uppercase");

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> policy.validate("ALLUPPERCASE1!"))
                .withMessageContaining("lowercase");

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> policy.validate("NoDigitsHere!!"))
                .withMessageContaining("digit");

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> policy.validate("NoSpecials1234"))
                .withMessageContaining("special character");
    }

    /**
     * Complexity rules alone happily accept {@code Password123!}, which appears in
     * every breach corpus and is therefore already compromised regardless of how many
     * character classes it contains.
     */
    @Test
    @DisplayName("rejects common passwords that pass complexity checks")
    void rejectsDenyListedPasswords() {
        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> policy.validate("Password123!"))
                .withMessageContaining("commonly used password");

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> policy.validate("AegisPay123!"))
                .withMessageContaining("commonly used password");
    }

    @Test
    @DisplayName("rejects long sequential runs")
    void rejectsSequentialRuns() {
        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> policy.validate("Xabcdefgh1!"))
                .withMessageContaining("sequential run");

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> policy.validate("X1234567y!A"))
                .withMessageContaining("sequential run");
    }

    @Test
    @DisplayName("rejects a single repeated character")
    void rejectsSingleRepeatedCharacter() {
        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> policy.validate("aaaaaaaaaaaaaa"))
                .withMessageContaining("repeated character");
    }

    @Test
    @DisplayName("rejects a null or blank password")
    void rejectsMissingPassword() {
        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> policy.validate(null))
                .withMessageContaining("required");

        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> policy.validate("   "));
    }

    /**
     * Reporting every violation at once means a user learns the whole policy from a
     * single response instead of discovering it one rejection at a time.
     */
    @Test
    @DisplayName("reports all violations at once")
    void reportsAllViolationsTogether() {
        assertThatExceptionOfType(BadRequestException.class)
                .isThrownBy(() -> policy.validate("abc"))
                .satisfies(ex -> assertThat(ex.getMessage())
                        .contains("at least 12 characters")
                        .contains("uppercase")
                        .contains("digit")
                        .contains("special character"));
    }
}
