package com.wtc.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingTest {

    @Test
    void keepsOnlyTheLastFourCharacters() {
        assertThat(Masking.mask("FR7630006000011234567890189")).isEqualTo("****0189");
        assertThat(Masking.last4("FR7630006000011234567890189")).isEqualTo("0189");
    }

    @Test
    void revealsNothingOfShortValues() {
        assertThat(Masking.mask("1234")).isEqualTo("****");
        assertThat(Masking.last4("1234")).isNull();
        assertThat(Masking.mask("")).isNull();
        assertThat(Masking.mask(null)).isNull();
        assertThat(Masking.last4(null)).isNull();
    }
}
