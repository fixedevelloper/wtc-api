package com.wtc.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    @Test
    void slugIsKebabCase() {
        assertThat(ErrorCode.COUNTRY_NOT_FOUND.slug()).isEqualTo("country-not-found");
    }

    @Test
    void everyCodeHasAnErrorStatusAndATitle() {
        Arrays.stream(ErrorCode.values())
                .forEach(
                        c -> {
                            assertThat(c.status().isError()).as(c.name()).isTrue();
                            assertThat(c.title()).as(c.name()).isNotBlank();
                        });
    }

    @Test
    void domainExceptionKeepsItsViolationsImmutable() {
        DomainException ex =
                new DomainException(
                        ErrorCode.VALIDATION_FAILED,
                        "x",
                        java.util.List.of(
                                new FieldViolation("amount", "Positive", "doit être > 0")));
        assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(ex.violations()).hasSize(1);
    }
}
