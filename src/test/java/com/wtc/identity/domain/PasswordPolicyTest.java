package com.wtc.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PasswordPolicyTest {

    @Test
    void acceptsALongPassphraseWithoutCompositionRules() {
        assertThatCode(
                        () ->
                                PasswordPolicy.requireStrong(
                                        "cheval agrafe pile bleu", "a@b.cd", "password"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "court1,TooShort",
        "motdepasse123,TooCommon",
        "Azertyuiop,TooCommon",
        "aaaaaaaaaaaa,TooRepetitive",
        "ababababab,TooRepetitive",
        "claire.dupont@example.com,ContainsEmail",
        "mon-claire.dupont-2026,ContainsEmail"
    })
    void rejectsWeakPasswordsWithTheReason(String password, String reason) {
        assertThatThrownBy(
                        () ->
                                PasswordPolicy.requireStrong(
                                        password, "claire.dupont@example.com", "newPassword"))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        e -> {
                            assertThat(e.code()).isEqualTo(ErrorCode.WEAK_PASSWORD);
                            assertThat(e.violations())
                                    .extracting(FieldViolation::code)
                                    .contains(reason);
                            assertThat(e.violations())
                                    .extracting(FieldViolation::field)
                                    .containsOnly("newPassword");
                        });
    }

    @Test
    void rejectsTooLongPasswordsToBoundHashingCost() {
        assertThatThrownBy(
                        () -> PasswordPolicy.requireStrong("x".repeat(129) + "y", null, "password"))
                .isInstanceOf(DomainException.class);
    }
}
