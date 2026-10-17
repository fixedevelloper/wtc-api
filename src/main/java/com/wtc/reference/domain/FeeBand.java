package com.wtc.reference.domain;

import java.math.BigDecimal;

/**
 * Tranche d'un barème : s'applique aux montants {@code min < montant ≤ max}. Le frais est {@code
 * pourcentage × montant + frais fixe}.
 */
public record FeeBand(
        long id,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        BigDecimal percent,
        BigDecimal fixedFee) {

    public boolean covers(BigDecimal amount) {
        return amount.compareTo(minAmount) > 0 && amount.compareTo(maxAmount) <= 0;
    }
}
