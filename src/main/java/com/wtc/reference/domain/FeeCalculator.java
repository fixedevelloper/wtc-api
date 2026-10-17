package com.wtc.reference.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Optional;

/**
 * Calcul des frais d'un transfert. Arrondi « demi supérieur » à la plus petite unité de la devise.
 */
public final class FeeCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private FeeCalculator() {}

    public static Optional<FeeBand> bandFor(Collection<FeeBand> bands, BigDecimal amount) {
        return bands.stream().filter(b -> b.covers(amount)).findFirst();
    }

    /** Frais = montant × pourcentage / 100 + frais fixe, arrondi demi supérieur dans la devise. */
    public static BigDecimal feeFor(BigDecimal amount, FeeBand band, String currencyCode) {
        BigDecimal proportional = amount.multiply(band.percent()).divide(HUNDRED);
        return CurrencyUnits.round(
                proportional.add(band.fixedFee()), currencyCode, RoundingMode.HALF_UP);
    }
}
