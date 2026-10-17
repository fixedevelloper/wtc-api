package com.wtc.transfer.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wtc.transfer.application.TransferAdminService;
import com.wtc.transfer.application.TransferRow;
import com.wtc.transfer.application.port.PayoutAttemptRepository.AttemptView;
import com.wtc.transfer.application.port.TransferRepository.HistoryEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Formes de réponse des transferts (contrat : TransferSummary, Transfer, AdminTransferSummary,
 * AdminTransfer).
 */
final class TransferResponses {

    private TransferResponses() {}

    record Money(String amount, String currency) {
        static Money of(BigDecimal amount, String currency) {
            // Montant à l'unité de la devise (XAF : entier, EUR : 2 décimales), pas à l'échelle
            // interne de la base.
            int digits =
                    Math.max(
                            java.util.Currency.getInstance(currency).getDefaultFractionDigits(), 0);
            return new Money(
                    amount.setScale(digits, java.math.RoundingMode.UNNECESSARY).toPlainString(),
                    currency);
        }
    }

    record CustomerRef(UUID id, String displayName) {}

    record StatusChange(String from, String to, String reason, String actor, Instant occurredAt) {
        static StatusChange from(HistoryEntry e) {
            return new StatusChange(e.from(), e.to(), e.reason(), e.actorType(), e.occurredAt());
        }
    }

    record PayoutAttempt(
            int attemptNo,
            String outcome,
            String providerStatus,
            Instant startedAt,
            Instant finishedAt) {
        static PayoutAttempt from(AttemptView a) {
            return new PayoutAttempt(
                    a.attemptNo(), a.outcome(), a.providerStatus(), a.startedAt(), a.finishedAt());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Summary(
            UUID id,
            String reference,
            String kind,
            String status,
            String senderName,
            String beneficiaryName,
            Money sourceAmount,
            Money fee,
            Money totalDebit,
            Money destinationAmount,
            Instant createdAt,
            Instant completedAt) {}

    record Detail(
            UUID id,
            String reference,
            String kind,
            String status,
            String senderName,
            String beneficiaryName,
            Money sourceAmount,
            Money fee,
            Money totalDebit,
            Money destinationAmount,
            Instant createdAt,
            Instant completedAt,
            JsonNode sender,
            JsonNode beneficiary,
            JsonNode destination,
            BigDecimal fxRate,
            String purposeCode,
            String sourceOfFundsCode,
            String relationshipCode,
            String comment,
            String failureReason,
            String providerReference,
            List<StatusChange> history) {}

    record AdminSummary(
            UUID id,
            String reference,
            String kind,
            String status,
            String senderName,
            String beneficiaryName,
            Money sourceAmount,
            Money fee,
            Money totalDebit,
            Money destinationAmount,
            Instant createdAt,
            Instant completedAt,
            CustomerRef customer,
            String providerReference,
            String failureReason) {}

    record AdminDetail(
            UUID id,
            String reference,
            String kind,
            String status,
            String senderName,
            String beneficiaryName,
            Money sourceAmount,
            Money fee,
            Money totalDebit,
            Money destinationAmount,
            Instant createdAt,
            Instant completedAt,
            JsonNode sender,
            JsonNode beneficiary,
            JsonNode destination,
            BigDecimal fxRate,
            String purposeCode,
            String sourceOfFundsCode,
            String relationshipCode,
            String comment,
            String failureReason,
            String providerReference,
            List<StatusChange> history,
            CustomerRef customer,
            List<PayoutAttempt> attempts) {}

    // ------------------------------------------------------------------ mapping

    static Summary summary(JsonMapper json, TransferRow t) {
        JsonNode snap = json.readTree(t.snapshotJson());
        return new Summary(
                t.publicId(),
                t.reference(),
                t.kind(),
                t.status().name(),
                name(snap.get("sender")),
                name(snap.get("beneficiary")),
                money(t.sourceAmount(), t.sourceCurrency()),
                money(t.feeAmount(), t.sourceCurrency()),
                money(t.totalDebit(), t.sourceCurrency()),
                destinationAmount(t),
                t.createdAt(),
                t.completedAt());
    }

    static Detail detail(JsonMapper json, TransferRow t, List<HistoryEntry> history) {
        JsonNode snap = json.readTree(t.snapshotJson());
        return new Detail(
                t.publicId(),
                t.reference(),
                t.kind(),
                t.status().name(),
                name(snap.get("sender")),
                name(snap.get("beneficiary")),
                money(t.sourceAmount(), t.sourceCurrency()),
                money(t.feeAmount(), t.sourceCurrency()),
                money(t.totalDebit(), t.sourceCurrency()),
                destinationAmount(t),
                t.createdAt(),
                t.completedAt(),
                snap.get("sender"),
                snap.get("beneficiary"),
                snap.get("destination"),
                t.fxRate() == null ? null : t.fxRate().stripTrailingZeros(),
                t.purposeCode(),
                t.sourceOfFundsCode(),
                t.relationshipCode(),
                t.comment(),
                t.failureReason(),
                null,
                history.stream().map(StatusChange::from).toList());
    }

    static AdminSummary adminSummary(JsonMapper json, TransferRow t) {
        JsonNode snap = json.readTree(t.snapshotJson());
        return new AdminSummary(
                t.publicId(),
                t.reference(),
                t.kind(),
                t.status().name(),
                name(snap.get("sender")),
                name(snap.get("beneficiary")),
                money(t.sourceAmount(), t.sourceCurrency()),
                money(t.feeAmount(), t.sourceCurrency()),
                money(t.totalDebit(), t.sourceCurrency()),
                destinationAmount(t),
                t.createdAt(),
                t.completedAt(),
                new CustomerRef(t.customerPublicId(), t.customerName()),
                t.providerReference(),
                t.failureReason());
    }

    static AdminDetail adminDetail(JsonMapper json, TransferAdminService.Detail d) {
        TransferRow t = d.transfer();
        JsonNode snap = json.readTree(t.snapshotJson());
        return new AdminDetail(
                t.publicId(),
                t.reference(),
                t.kind(),
                t.status().name(),
                name(snap.get("sender")),
                name(snap.get("beneficiary")),
                money(t.sourceAmount(), t.sourceCurrency()),
                money(t.feeAmount(), t.sourceCurrency()),
                money(t.totalDebit(), t.sourceCurrency()),
                destinationAmount(t),
                t.createdAt(),
                t.completedAt(),
                snap.get("sender"),
                snap.get("beneficiary"),
                snap.get("destination"),
                t.fxRate() == null ? null : t.fxRate().stripTrailingZeros(),
                t.purposeCode(),
                t.sourceOfFundsCode(),
                t.relationshipCode(),
                t.comment(),
                t.failureReason(),
                t.providerReference(),
                d.history().stream().map(StatusChange::from).toList(),
                new CustomerRef(t.customerPublicId(), t.customerName()),
                d.attempts().stream().map(PayoutAttempt::from).toList());
    }

    private static Money money(BigDecimal amount, String currency) {
        return Money.of(amount, currency);
    }

    private static Money destinationAmount(TransferRow t) {
        return t.destinationAmount() == null
                ? null
                : Money.of(t.destinationAmount(), t.destinationCurrency());
    }

    private static String name(JsonNode party) {
        if (party == null) {
            return null;
        }
        if ("BUSINESS".equals(party.path("kind").asString()) && party.has("businessName")) {
            return party.get("businessName").asString();
        }
        return (party.path("firstName").asString("") + " " + party.path("lastName").asString(""))
                .trim();
    }
}
