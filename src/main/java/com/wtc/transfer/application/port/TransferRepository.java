package com.wtc.transfer.application.port;

import com.wtc.transfer.application.NewTransfer;
import com.wtc.transfer.application.TransferRow;
import com.wtc.transfer.domain.TransferStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository {

    record Filter(
            TransferStatus status, UUID customerId, Instant from, Instant to, String search) {}

    record Page(List<TransferRow> content, long total) {
        public Page {
            content = List.copyOf(content);
        }
    }

    long nextReferenceNumber();

    /**
     * @throws org.springframework.dao.DuplicateKeyException si (client, clé d'idempotence) existe
     *     déjà
     */
    long insert(NewTransfer transfer);

    Optional<TransferRow> findById(long id);

    Optional<TransferRow> findByPublicId(UUID publicId);

    /**
     * Verrouille la ligne jusqu'à la fin de la transaction : deux travailleurs ne traitent jamais
     * le même transfert.
     */
    Optional<TransferRow> lock(long id);

    Optional<TransferRow> findByIdempotency(long customerId, String key);

    /**
     * Passage d'état atomique et conditionnel : faux si le transfert n'était plus dans l'état
     * attendu.
     */
    boolean transition(long id, TransferStatus from, TransferStatus to, String failureReason);

    void appendHistory(
            long transferId,
            TransferStatus from,
            TransferStatus to,
            String reason,
            String actorType,
            UUID actor,
            String metadataJson);

    void markProviderOrder(
            long id,
            String providerReference,
            String providerStatus,
            BigDecimal providerFee,
            BigDecimal amountReceived);

    void markConfirmed(long id);

    void markPolled(long id, String providerStatus);

    void setHold(long id, String hold);

    void clearHold(long id);

    /**
     * FAILED → REFUNDED avec l'écriture de remboursement, en une seule instruction (contrainte de
     * cohérence de la base).
     */
    boolean refundTransition(long id, long ledgerTransactionId);

    Page listOfCustomer(long customerId, int page, int size, boolean newestFirst);

    Page search(Filter filter, int page, int size, boolean newestFirst);

    /**
     * Transferts à interroger : en cours chez le fournisseur, pas bloqués (sauf par conformité),
     * non interrogés depuis {@code before}.
     */
    List<TransferRow> dueForPolling(Instant before, int limit);

    record HistoryEntry(
            String from, String to, String reason, String actorType, Instant occurredAt) {}

    List<HistoryEntry> history(long transferId);
}
