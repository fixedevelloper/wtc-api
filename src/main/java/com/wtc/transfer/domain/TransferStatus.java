package com.wtc.transfer.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * États d'un transfert et transitions autorisées (machine d'états de l'ERD).
 *
 * <pre>
 * CREATED → PAYOUT_PENDING → PROCESSING → COMPLETED
 *                 ↓               ↓
 *               FAILED  ←─────────┘            FAILED → REFUNDED
 * CREATED → CANCELLED
 * </pre>
 *
 * {@code COMPLETED}, {@code REFUNDED} et {@code CANCELLED} sont terminaux. Seul {@code FAILED} mène
 * à {@code REFUNDED}, et {@code FAILED} lui-même n'est atteint que sur un statut <b>confirmé</b> du
 * fournisseur (refus explicite ou annulation) ou sur décision humaine : une réponse ambiguë ne fait
 * jamais échouer un transfert.
 */
public enum TransferStatus {
    CREATED,
    PAYOUT_PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    REFUNDED,
    CANCELLED;

    private static final Set<TransferStatus> NONE = EnumSet.noneOf(TransferStatus.class);

    public Set<TransferStatus> next() {
        return switch (this) {
            case CREATED -> EnumSet.of(PAYOUT_PENDING, CANCELLED);
            case PAYOUT_PENDING -> EnumSet.of(PROCESSING, FAILED);
            case PROCESSING -> EnumSet.of(COMPLETED, FAILED);
            case FAILED -> EnumSet.of(REFUNDED);
            case COMPLETED, REFUNDED, CANCELLED -> NONE;
        };
    }

    public boolean canMoveTo(TransferStatus target) {
        return next().contains(target);
    }

    public boolean isTerminal() {
        return next().isEmpty();
    }

    /** Le client a été débité et l'argent n'est ni livré ni rendu : le transfert est « ouvert ». */
    public boolean isOpen() {
        return this == CREATED || this == PAYOUT_PENDING || this == PROCESSING;
    }
}
