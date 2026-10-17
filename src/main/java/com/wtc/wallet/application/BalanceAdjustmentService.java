package com.wtc.wallet.application;

import com.wtc.shared.audit.AuditTrail;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import com.wtc.shared.security.Tokens;
import com.wtc.wallet.application.port.AdjustmentRepository;
import com.wtc.wallet.application.port.AdjustmentRepository.Found;
import com.wtc.wallet.application.port.AdjustmentRepository.Pending;
import com.wtc.wallet.application.port.WalletRepository.WalletRow;
import com.wtc.wallet.domain.Direction;
import com.wtc.wallet.domain.PostingRules;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ajustements manuels de solde, avec <b>double validation</b> : une personne demande, une
 * <i>autre</i> approuve. Rien ne bouge au grand livre avant l'approbation. La règle « auteur ≠
 * approbateur » est appliquée ici <i>et</i> par une contrainte de la base.
 */
@Service
public class BalanceAdjustmentService {

    public static final String COUNTERPART = "PLATFORM_ADJUSTMENTS";

    private final AdjustmentRepository adjustments;
    private final WalletService wallets;
    private final LedgerService ledger;
    private final AuditTrail audit;

    BalanceAdjustmentService(
            AdjustmentRepository adjustments,
            WalletService wallets,
            LedgerService ledger,
            AuditTrail audit) {
        this.adjustments = adjustments;
        this.wallets = wallets;
        this.ledger = ledger;
        this.audit = audit;
    }

    public record NewAdjustment(
            UUID customerId, String direction, BigDecimal amount, String currency, String reason) {}

    @PreAuthorize("hasAuthority('BALANCE_ADJUST_REQUEST')")
    @Transactional(readOnly = true)
    public Page<AdjustmentView> list(String search, int page, int size, boolean newestFirst) {
        return adjustments.list(search, page, size, newestFirst);
    }

    /**
     * Demande un ajustement. Rejouer la même demande (même {@code idempotencyKey}, même contenu)
     * renvoie la demande initiale ; la même clé avec un contenu différent est refusée.
     */
    @PreAuthorize("hasAuthority('BALANCE_ADJUST_REQUEST')")
    @Transactional
    public AdjustmentView request(UUID actor, String idempotencyKey, NewAdjustment n) {
        Direction direction = parseDirection(n.direction());
        String currency =
                n.currency() == null || n.currency().isBlank()
                        ? wallets.primaryCurrency(n.customerId())
                        : n.currency().toUpperCase(Locale.ROOT);
        PostingRules.requireAmount(n.amount(), currency);
        String reason = n.reason() == null ? "" : n.reason().strip();
        if (reason.length() < 5) {
            throw new DomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "Le motif doit faire au moins 5 caractères.",
                    List.of(new FieldViolation("reason", "TooShort", "5 caractères minimum.")));
        }
        String fingerprint =
                Tokens.sha256(
                        String.join(
                                "|",
                                n.customerId().toString(),
                                direction.name(),
                                n.amount().stripTrailingZeros().toPlainString(),
                                currency,
                                reason));

        var replay = adjustments.findByIdempotency(actor, idempotencyKey);
        if (replay.isPresent()) {
            return sameRequest(replay.get(), fingerprint);
        }
        WalletRow wallet = wallets.ensureWallet(n.customerId(), currency);
        UUID id;
        try {
            id =
                    adjustments.insert(
                            wallet.id(),
                            direction.name(),
                            n.amount(),
                            currency,
                            reason,
                            actor,
                            idempotencyKey,
                            fingerprint);
        } catch (DuplicateKeyException race) {
            // Deux requêtes simultanées avec la même clé : l'index unique a désigné la gagnante, on
            // renvoie son résultat.
            return sameRequest(
                    adjustments.findByIdempotency(actor, idempotencyKey).orElseThrow(),
                    fingerprint);
        }
        audit.recordAs(
                actor,
                "ADJUSTMENT_REQUESTED",
                "BALANCE_ADJUSTMENT",
                id.toString(),
                null,
                "{\"direction\":\""
                        + direction
                        + "\",\"currency\":\""
                        + currency
                        + "\",\"amount\":"
                        + n.amount().toPlainString()
                        + "}");
        return adjustments.find(id).orElseThrow();
    }

    @PreAuthorize("hasAuthority('BALANCE_ADJUST_APPROVE')")
    @Transactional
    public AdjustmentView approve(UUID actor, UUID adjustmentId, String note) {
        Pending pending = decidable(actor, adjustmentId);
        Direction direction = Direction.valueOf(pending.direction());
        // Débit ou crédit du portefeuille, contrepartie sur le compte « ajustements » de la
        // plateforme.
        PostingResult posted =
                ledger.post(
                        new PostingRequest(
                                "adjustment:" + pending.publicId(),
                                LedgerKind.ADJUSTMENT,
                                "Ajustement manuel approuvé",
                                List.of(
                                        new PostingLeg(
                                                AccountRef.wallet(pending.walletId()),
                                                direction,
                                                pending.amount(),
                                                pending.currency()),
                                        new PostingLeg(
                                                AccountRef.platform(COUNTERPART),
                                                direction.opposite(),
                                                pending.amount(),
                                                pending.currency())),
                                actor,
                                "BALANCE_ADJUSTMENT",
                                pending.id()));
        adjustments.markApproved(
                pending.id(),
                actor,
                posted.ledgerTransactionId(),
                note == null || note.isBlank() ? null : note.strip());
        audit.recordAs(
                actor,
                "ADJUSTMENT_APPROVED",
                "BALANCE_ADJUSTMENT",
                adjustmentId.toString(),
                null,
                null);
        return adjustments.find(adjustmentId).orElseThrow();
    }

    @PreAuthorize("hasAuthority('BALANCE_ADJUST_APPROVE')")
    @Transactional
    public AdjustmentView reject(UUID actor, UUID adjustmentId, String reason) {
        Pending pending = decidable(actor, adjustmentId);
        if (reason == null || reason.strip().length() < 5) {
            throw new DomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "Le motif du refus doit faire au moins 5 caractères.",
                    List.of(new FieldViolation("reason", "TooShort", "5 caractères minimum.")));
        }
        adjustments.markRejected(pending.id(), actor, reason.strip());
        audit.recordAs(
                actor,
                "ADJUSTMENT_REJECTED",
                "BALANCE_ADJUSTMENT",
                adjustmentId.toString(),
                null,
                null);
        return adjustments.find(adjustmentId).orElseThrow();
    }

    // ------------------------------------------------------------------ interne

    private Pending decidable(UUID actor, UUID adjustmentId) {
        Pending pending =
                adjustments
                        .lock(adjustmentId)
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.ADJUSTMENT_NOT_FOUND,
                                                "Ajustement introuvable."));
        if (!"PENDING".equals(pending.status())) {
            throw new DomainException(
                    ErrorCode.ADJUSTMENT_ALREADY_DECIDED, "Cet ajustement a déjà été traité.");
        }
        if (pending.requestedBy().equals(actor)) {
            throw new DomainException(
                    ErrorCode.FOUR_EYES_REQUIRED,
                    "Une autre personne que le demandeur doit traiter cet ajustement.");
        }
        return pending;
    }

    private static AdjustmentView sameRequest(Found found, String fingerprint) {
        if (!fingerprint.equals(found.fingerprint())) {
            throw new DomainException(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Cette clé d'idempotence a déjà servi pour une autre demande.");
        }
        return found.view();
    }

    private static Direction parseDirection(String value) {
        try {
            return Direction.valueOf(value == null ? "" : value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "Sens invalide.",
                    List.of(new FieldViolation("direction", "Invalid", "CREDIT ou DEBIT.")));
        }
    }
}
