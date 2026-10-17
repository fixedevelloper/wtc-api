package com.wtc.transfer.application;

import com.wtc.shared.audit.AuditTrail;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import com.wtc.shared.outbox.OutboxRepository;
import com.wtc.transfer.application.port.PayoutAttemptRepository;
import com.wtc.transfer.application.port.PayoutAttemptRepository.AttemptView;
import com.wtc.transfer.application.port.TransferRepository;
import com.wtc.transfer.application.port.TransferRepository.HistoryEntry;
import com.wtc.transfer.domain.TransferStatus;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Actions humaines sur les transferts bloqués ou échoués (permission {@code TRANSFER_REFUND}). Ce
 * sont les seules portes qui permettent de rembourser un transfert dont l'issue chez le fournisseur
 * n'est pas confirmée, et elles exigent un motif et laissent une trace d'audit.
 */
@Service
public class TransferAdminService {

    private final TransferRepository transfers;
    private final PayoutAttemptRepository attempts;
    private final OutboxRepository outbox;
    private final TransferLifecycle lifecycle;
    private final PayoutProcessor processor;
    private final AuditTrail audit;
    private final Clock clock;
    private final TransactionTemplate tx;

    TransferAdminService(
            TransferRepository transfers,
            PayoutAttemptRepository attempts,
            OutboxRepository outbox,
            TransferLifecycle lifecycle,
            PayoutProcessor processor,
            AuditTrail audit,
            Clock clock,
            PlatformTransactionManager txManager) {
        this.transfers = transfers;
        this.attempts = attempts;
        this.outbox = outbox;
        this.lifecycle = lifecycle;
        this.processor = processor;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    public record Detail(
            TransferRow transfer, List<HistoryEntry> history, List<AttemptView> attempts) {
        public Detail {
            history = List.copyOf(history);
            attempts = List.copyOf(attempts);
        }
    }

    @PreAuthorize("hasAuthority('TRANSFER_READ')")
    public Detail detail(UUID transferId) {
        TransferRow t = transfers.findByPublicId(transferId).orElseThrow(() -> notFound());
        return new Detail(t, transfers.history(t.id()), attempts.views(t.id()));
    }

    /**
     * Rembourse un transfert. Autorisé quand l'échec est confirmé ({@code FAILED}) ou, sur décision
     * humaine motivée, quand l'ordre n'a jamais été créé chez le fournisseur ({@code
     * PAYOUT_PENDING} bloqué en « création incertaine » : l'équipe a vérifié dans le back-office de
     * Wace qu'aucun ordre n'existe). Jamais pour un ordre en cours chez le fournisseur.
     */
    @PreAuthorize("hasAuthority('TRANSFER_REFUND')")
    public Detail refund(UUID actor, UUID transferId, String reason) {
        if (reason == null || reason.strip().length() < 5) {
            throw new DomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "Le motif doit faire au moins 5 caractères.",
                    List.of(new FieldViolation("reason", "TooShort", "5 caractères minimum.")));
        }
        String why = reason.strip();
        tx.executeWithoutResult(
                s -> {
                    TransferRow t =
                            transfers
                                    .lock(
                                            transfers
                                                    .findByPublicId(transferId)
                                                    .orElseThrow(TransferAdminService::notFound)
                                                    .id())
                                    .orElseThrow();
                    if (t.status() == TransferStatus.PAYOUT_PENDING
                            && "AMBIGUOUS_CREATE".equals(t.payoutHold())) {
                        lifecycle.move(
                                t,
                                TransferStatus.FAILED,
                                "Vérifié manuellement : aucun ordre chez le fournisseur. " + why,
                                "ADMIN",
                                actor,
                                null);
                        t = transfers.findById(t.id()).orElseThrow();
                    }
                    lifecycle.refund(t, "ADMIN", actor, why);
                    audit.recordAs(
                            actor,
                            "TRANSFER_REFUNDED_MANUALLY",
                            "TRANSFER",
                            transferId.toString(),
                            null,
                            null);
                });
        return detail(transferId);
    }

    /**
     * Resynchronise un transfert : interroge immédiatement le fournisseur. Si l'ordre d'un
     * transfert bloqué en « création incertaine » a été retrouvé dans le back-office de Wace,
     * {@code providerReference} le rattache et reprend le suivi normal (confirmation puis statuts).
     */
    @PreAuthorize("hasAuthority('TRANSFER_REFUND')")
    public Detail sync(UUID actor, UUID transferId, String providerReference) {
        tx.executeWithoutResult(
                s -> {
                    TransferRow t =
                            transfers
                                    .lock(
                                            transfers
                                                    .findByPublicId(transferId)
                                                    .orElseThrow(TransferAdminService::notFound)
                                                    .id())
                                    .orElseThrow();
                    if (providerReference != null && !providerReference.isBlank()) {
                        if (t.status() != TransferStatus.PAYOUT_PENDING
                                || !"AMBIGUOUS_CREATE".equals(t.payoutHold())) {
                            throw new DomainException(
                                    ErrorCode.CONFLICT,
                                    "Seul un transfert bloqué en « création incertaine » peut"
                                            + " recevoir une référence du fournisseur.");
                        }
                        transfers.markProviderOrder(
                                t.id(),
                                providerReference.strip(),
                                "WAITING_CONFIRMATION",
                                null,
                                null);
                        transfers.clearHold(t.id());
                        lifecycle.move(
                                t,
                                TransferStatus.PROCESSING,
                                "Ordre rattaché manuellement : " + providerReference.strip(),
                                "ADMIN",
                                actor,
                                null);
                        outbox.enqueue(
                                TransferService.OUTBOX_AGGREGATE,
                                t.id(),
                                PayoutProcessor.EVENT_CONFIRM,
                                clock.instant());
                        audit.recordAs(
                                actor,
                                "TRANSFER_PROVIDER_REFERENCE_ATTACHED",
                                "TRANSFER",
                                transferId.toString(),
                                null,
                                null);
                    } else if (t.status() != TransferStatus.PROCESSING
                            || t.providerReference() == null) {
                        throw new DomainException(
                                ErrorCode.CONFLICT,
                                "Ce transfert n'a pas d'ordre chez le fournisseur à interroger.");
                    }
                });
        TransferRow t =
                transfers.findByPublicId(transferId).orElseThrow(TransferAdminService::notFound);
        processor.pollOne(t.id());
        return detail(transferId);
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.TRANSFER_NOT_FOUND, "Transfert introuvable.");
    }
}
