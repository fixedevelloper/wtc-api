package com.wtc.reference.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wtc.shared.error.DomainException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class CurrencyUnitsTest {

    @Test
    void knowsTheDecimalsOfEachCurrency() {
        assertThat(CurrencyUnits.fractionDigits("XAF")).isZero();
        assertThat(CurrencyUnits.fractionDigits("XOF")).isZero();
        assertThat(CurrencyUnits.fractionDigits("EUR")).isEqualTo(2);
        assertThat(CurrencyUnits.fractionDigits("USD")).isEqualTo(2);
    }

    @Test
    void roundsWithTheRequestedMode() {
        assertThat(CurrencyUnits.round(new BigDecimal("10.005"), "EUR", RoundingMode.HALF_UP))
                .isEqualByComparingTo("10.01");
        assertThat(CurrencyUnits.round(new BigDecimal("10.009"), "EUR", RoundingMode.DOWN))
                .isEqualByComparingTo("10.00");
    }

    @Test
    void refusesUnknownCurrencies() {
        assertThatThrownBy(() -> CurrencyUnits.fractionDigits("ZZZ"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> CurrencyUnits.fractionDigits("£"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> CurrencyUnits.fractionDigits(null))
                .isInstanceOf(DomainException.class);
    }
}
