package com.wtc.transfer.application;

import java.math.BigDecimal;
import java.util.UUID;

/** Données d'un transfert à créer (le statut initial est {@code CREATED}). */
public record NewTransfer(
        UUID publicId,
        String reference,
        long customerId,
        long walletId,
        long senderPartyId,
        long beneficiaryPartyId,
        long destinationId,
        String kind,
        String fundingSource,
        String partyTypePair,
        BigDecimal sourceAmount,
        BigDecimal feeAmount,
        String sourceCurrency,
        BigDecimal destinationAmount,
        String destinationCurrency,
        BigDecimal fxRate,
        long feeScheduleId,
        String purposeCode,
        String sourceOfFundsCode,
        String relationshipCode,
        String comment,
        String idempotencyKey,
        String requestFingerprint,
        String snapshotJson,
        long debitLedgerTransactionId,
        UUID createdBy) {}
