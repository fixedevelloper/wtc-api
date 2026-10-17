package com.wtc.reference.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FeeCalculatorTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static final FeeBand LOW = new FeeBand(1, d("0"), d("100000"), d("5"), d("0"));
    private static final FeeBand HIGH =
            new FeeBand(2, d("100000"), d("1000000"), d("3.5"), d("500"));

    @Test
    void aBandCoversItsUpperBoundButNotItsLowerBound() {
        assertThat(LOW.covers(d("0.0001"))).isTrue();
        assertThat(LOW.covers(d("100000"))).as("max inclus").isTrue();
        assertThat(LOW.covers(d("0"))).as("min exclu").isFalse();
        assertThat(LOW.covers(d("100000.0001"))).isFalse();
        assertThat(HIGH.covers(d("100000")))
                .as("la frontière appartient à la tranche basse, jamais aux deux")
                .isFalse();
    }

    @Test
    void picksTheOnlyBandThatCoversTheAmount() {
        assertThat(FeeCalculator.bandFor(List.of(LOW, HIGH), d("100000")).orElseThrow().id())
                .isEqualTo(1);
        assertThat(FeeCalculator.bandFor(List.of(LOW, HIGH), d("100001")).orElseThrow().id())
                .isEqualTo(2);
        assertThat(FeeCalculator.bandFor(List.of(LOW, HIGH), d("1000001"))).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1} à {2} % + {3} fixe = {4}")
    @CsvSource({
        "XAF, 10000, 5, 0, 500",
        "XAF, 10001, 5, 0, 500",
        "XAF, 10010, 5, 0, 501",
        "XAF, 100001, 3.5, 500, 4000",
        "EUR, 100.00, 4, 0, 4.00",
        "EUR, 33.33, 5, 0, 1.67",
        "EUR, 10.10, 5, 0.25, 0.76",
        "USD, 0.10, 5, 0, 0.01"
    })
    void feeIsRoundedHalfUpToTheCurrencyUnit(
            String currency, String amount, String percent, String fixed, String expected) {
        FeeBand band = new FeeBand(1, d("0"), d("999999999"), d(percent), d(fixed));
        assertThat(FeeCalculator.feeFor(d(amount), band, currency)).isEqualByComparingTo(expected);
        assertThat(FeeCalculator.feeFor(d(amount), band, currency).scale())
                .isEqualTo(CurrencyUnits.fractionDigits(currency));
    }

    @Test
    void aZeroPercentBandWithAFixedFeeChargesOnlyTheFixedFee() {
        FeeBand flat = new FeeBand(3, d("0"), d("50000"), d("0"), d("250"));
        assertThat(FeeCalculator.feeFor(d("12345"), flat, "XAF")).isEqualByComparingTo("250");
    }
}
