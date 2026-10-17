package com.wtc.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PageParamsTest {

    private static final Set<String> ALLOWED = Set.of("email", "createdAt");

    @Test
    void usesTheDefaultWhenNoSortIsGiven() {
        assertThat(PageParams.parse(null, ALLOWED, "createdAt", false))
                .isEqualTo(new PageParams("createdAt", false));
        assertThat(PageParams.parse("  ", ALLOWED, "email", true))
                .isEqualTo(new PageParams("email", true));
    }

    @Test
    void parsesFieldAndDirection() {
        assertThat(PageParams.parse("email,desc", ALLOWED, "createdAt", true))
                .isEqualTo(new PageParams("email", false));
        assertThat(PageParams.parse("email,ASC", ALLOWED, "createdAt", false))
                .isEqualTo(new PageParams("email", true));
        assertThat(PageParams.parse("email", ALLOWED, "createdAt", false))
                .isEqualTo(new PageParams("email", true));
    }

    @Test
    void refusesUnknownFieldsAndDirections() {
        assertThatThrownBy(() -> PageParams.parse("password_hash,asc", ALLOWED, "createdAt", false))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> PageParams.parse("email,sideways", ALLOWED, "createdAt", false))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        () ->
                                PageParams.parse(
                                        "email; DROP TABLE users", ALLOWED, "createdAt", false))
                .isInstanceOf(DomainException.class);
    }
}
