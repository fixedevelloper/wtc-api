package com.wtc.wallet.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Demande d'ajustement manuel de solde et son état. {@code requestedBy} et {@code decidedBy} sont
 * des adresses e-mail.
 */
public record AdjustmentView(
        UUID id,
        UUID customerId,
        String customerName,
        String direction,
        BigDecimal amount,
        String currency,
        String reason,
        String status,
        String requestedBy,
        String decidedBy,
        Instant decidedAt,
        Instant createdAt) {}
