package com.wtc.wallet.application;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ligne du relevé d'un portefeuille : {@code CREDIT} = argent entrant, {@code DEBIT} = argent
 * sortant.
 */
public record LedgerEntryView(
        long id,
        Instant occurredAt,
        String kind,
        String direction,
        BigDecimal amount,
        String currency,
        String description,
        String reference) {}
