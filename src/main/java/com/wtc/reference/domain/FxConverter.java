package com.wtc.reference.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Conversion de devises. Le montant reçu est arrondi <b>vers le bas</b> : on ne promet jamais au
 * bénéficiaire plus que ce que le taux donne. Un taux inverse ({@code 1 / taux}) est calculé avec
 * 20 chiffres significatifs.
 */
public final class FxConverter {

    private static final MathContext PRECISION = new MathContext(20, RoundingMode.HALF_EVEN);

    private FxConverter() {}

    /**
     * Taux appliqué de {@code from} vers {@code to} : 1 pour une même devise, sinon direct, sinon
     * inverse.
     */
    public static Optional<BigDecimal> rate(
            String from, String to, Optional<BigDecimal> direct, Optional<BigDecimal> inverse) {
        if (from.equals(to)) {
            return Optional.of(BigDecimal.ONE);
        }
        if (direct.isPresent()) {
            return direct;
        }
        return inverse.map(r -> BigDecimal.ONE.divide(r, PRECISION));
    }

    public static BigDecimal convert(BigDecimal amount, BigDecimal rate, String toCurrency) {
        return CurrencyUnits.round(amount.multiply(rate), toCurrency, RoundingMode.DOWN);
    }
}
