package com.wtc.wallet.application.port;

import com.wtc.wallet.application.AdjustmentView;
import com.wtc.wallet.application.Page;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface AdjustmentRepository {

    /** Ligne verrouillée pour décision : le portefeuille visé et l'auteur de la demande. */
    record Pending(
            long id,
            UUID publicId,
            long walletId,
            String direction,
            BigDecimal amount,
            String currency,
            String status,
            UUID requestedBy) {}

    record Found(AdjustmentView view, String fingerprint) {}

    Optional<Found> findByIdempotency(UUID requestedBy, String key);

    UUID insert(
            long walletId,
            String direction,
            BigDecimal amount,
            String currency,
            String reason,
            UUID requestedBy,
            String key,
            String fingerprint);

    Optional<AdjustmentView> find(UUID publicId);

    /**
     * Verrouille la demande jusqu'à la fin de la transaction (deux décisions simultanées se
     * sérialisent).
     */
    Optional<Pending> lock(UUID publicId);

    void markApproved(long id, UUID decidedBy, long ledgerTransactionId, String note);

    void markRejected(long id, UUID decidedBy, String note);

    Page<AdjustmentView> list(String search, int page, int size, boolean newestFirst);
}
