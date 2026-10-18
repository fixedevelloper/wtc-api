package com.wtc.wallet.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Recharge de portefeuille vue par le back-office ; {@code completedAt} n'existe que si elle est
 * terminée.
 */
public record TopUpView(
        UUID id,
        String reference,
        String method,
        BigDecimal amount,
        String currency,
        String status,
        String failureReason,
        Instant createdAt,
        Instant completedAt,
        UUID customerId,
        String customerName) {}
