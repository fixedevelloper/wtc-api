package com.wtc.transfer.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Demande de transfert.
 *
 * @param expectedTotalDebit total vu par le client dans son devis : si le serveur calcule autre
 *     chose, la création est refusée (le client revalide) plutôt que de débiter un montant
 *     inattendu
 * @param offlineReceiptReference justificatif d'un paiement reçu hors plateforme (saisie par le
 *     back-office uniquement)
 */
public record CreateTransferCommand(
        UUID senderId,
        UUID beneficiaryId,
        UUID destinationId,
        BigDecimal sourceAmount,
        String purposeCode,
        String sourceOfFundsCode,
        String relationshipCode,
        String comment,
        BigDecimal expectedTotalDebit,
        boolean offlineReceipt,
        String offlineReceiptReference) {}
