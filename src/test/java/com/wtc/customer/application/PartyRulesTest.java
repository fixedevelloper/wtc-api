package com.wtc.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PartyRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    @Test
    void anIndividualNeedsFirstAndLastName() {
        assertThatCode(
                        () ->
                                PartyRules.requireConsistent(
                                        "INDIVIDUAL", "Claire", "Dupont", null, null, null, TODAY))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                PartyRules.requireConsistent(
                                        "INDIVIDUAL", " ", null, null, null, null, TODAY))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        e -> {
                            assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                            assertThat(e.violations())
                                    .extracting(FieldViolation::field)
                                    .containsExactlyInAnyOrder("firstName", "lastName");
                        });
    }

    @Test
    void aBusinessNeedsABusinessName() {
        assertThatCode(
                        () ->
                                PartyRules.requireConsistent(
                                        "BUSINESS", null, null, "ACME SARL", null, null, TODAY))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                PartyRules.requireConsistent(
                                        "BUSINESS", "Claire", "Dupont", null, null, null, TODAY))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        e ->
                                assertThat(e.violations())
                                        .extracting(FieldViolation::field)
                                        .containsExactly("businessName"));
    }

    @Test
    void datesMustBePlausible() {
        assertThatThrownBy(
                        () ->
                                PartyRules.requireConsistent(
                                        "INDIVIDUAL",
                                        "A",
                                        "B",
                                        null,
                                        TODAY.plusDays(1),
                                        null,
                                        TODAY))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        () ->
                                PartyRules.requireConsistent(
                                        "INDIVIDUAL",
                                        "A",
                                        "B",
                                        null,
                                        LocalDate.of(1850, 1, 1),
                                        null,
                                        TODAY))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        () ->
                                PartyRules.requireConsistent(
                                        "BUSINESS",
                                        null,
                                        null,
                                        "X",
                                        null,
                                        TODAY.plusYears(1),
                                        TODAY))
                .isInstanceOf(DomainException.class);
        assertThatCode(
                        () ->
                                PartyRules.requireConsistent(
                                        "INDIVIDUAL", "A", "B", null, TODAY, null, TODAY))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnknownKindIsRefused() {
        assertThatThrownBy(
                        () ->
                                PartyRules.requireConsistent(
                                        "ALIEN", "A", "B", null, null, null, TODAY))
                .isInstanceOf(DomainException.class);
    }
}
