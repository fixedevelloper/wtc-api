package com.wtc.reference.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FxConverterTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void sameCurrencyNeedsNoRate() {
        assertThat(FxConverter.rate("XAF", "XAF", Optional.empty(), Optional.empty()))
                .contains(BigDecimal.ONE);
    }

    @Test
    void prefersTheDirectRateThenFallsBackToTheInverse() {
        assertThat(
                        FxConverter.rate(
                                "EUR", "XAF", Optional.of(d("655.957")), Optional.of(d("0.0015"))))
                .contains(d("655.957"));
        BigDecimal inverse =
                FxConverter.rate("XAF", "EUR", Optional.empty(), Optional.of(d("655.957")))
                        .orElseThrow();
        assertThat(inverse)
                .isCloseTo(d("0.001524490"), org.assertj.core.data.Offset.offset(d("0.000000001")));
        assertThat(FxConverter.rate("XAF", "USD", Optional.empty(), Optional.empty())).isEmpty();
    }

    @Test
    void destinationAmountIsRoundedDownNeverUp() {
        assertThat(FxConverter.convert(d("100.00"), d("655.957"), "XAF"))
                .as("65595,7 → 65595")
                .isEqualByComparingTo("65595");
        assertThat(FxConverter.convert(d("10000"), d("0.00152449"), "EUR"))
                .as("15,2449 → 15,24")
                .isEqualByComparingTo("15.24");
        assertThat(FxConverter.convert(d("10000"), d("0.0015299"), "EUR"))
                .as("15,299 → 15,29 (jamais 15,30)")
                .isEqualByComparingTo("15.29");
        assertThat(FxConverter.convert(d("1"), d("0.99999"), "XAF")).isEqualByComparingTo("0");
    }

    @Test
    void aRoundTripNeverCreatesMoney() {
        BigDecimal rate = d("655.957");
        BigDecimal euros = d("123.45");
        BigDecimal xaf = FxConverter.convert(euros, rate, "XAF");
        BigDecimal back =
                FxConverter.convert(
                        xaf,
                        FxConverter.rate("XAF", "EUR", Optional.empty(), Optional.of(rate))
                                .orElseThrow(),
                        "EUR");
        assertThat(back).isLessThanOrEqualTo(euros);
    }
}
