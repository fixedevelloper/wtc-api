package com.wtc.reference.application;

import java.math.BigDecimal;

/**
 * Résultat d'un calcul de frais et de change. Tous les montants sont déjà arrondis à l'unité de
 * leur devise : frais demi supérieur, montant reçu vers le bas.
 *
 * @param feePercent pourcentage de la tranche appliquée
 * @param fxRate taux appliqué de la devise source vers la devise de destination (1 si identiques)
 */
public record PricingQuote(
        String sourceCurrency,
        BigDecimal sourceAmount,
        BigDecimal fee,
        BigDecimal feePercent,
        BigDecimal totalDebit,
        String destinationCurrency,
        BigDecimal destinationAmount,
        BigDecimal fxRate,
        long feeScheduleId) {}
