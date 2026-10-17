package com.wtc.transfer.application;

import com.wtc.transfer.domain.TransferStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Un transfert tel que stocké. Les montants sont dans la devise source (frais compris dans {@code
 * totalDebit}).
 */
public record TransferRow(
        long id,
        UUID publicId,
        String reference,
        long customerId,
        UUID customerPublicId,
        String customerName,
        Long walletId,
        long senderPartyId,
        long beneficiaryPartyId,
        long destinationId,
        String kind,
        String fundingSource,
        String partyTypePair,
        BigDecimal sourceAmount,
        BigDecimal feeAmount,
        BigDecimal totalDebit,
        String sourceCurrency,
        BigDecimal destinationAmount,
        String destinationCurrency,
        BigDecimal fxRate,
        Long feeScheduleId,
        String purposeCode,
        String sourceOfFundsCode,
        String relationshipCode,
        String comment,
        TransferStatus status,
        String failureReason,
        String providerReference,
        String providerStatus,
        Instant providerConfirmedAt,
        BigDecimal providerFee,
        BigDecimal providerAmountReceived,
        String payoutHold,
        String snapshotJson,
        Long debitLedgerTransactionId,
        Long refundLedgerTransactionId,
        String requestFingerprint,
        Instant createdAt,
        Instant completedAt) {}
