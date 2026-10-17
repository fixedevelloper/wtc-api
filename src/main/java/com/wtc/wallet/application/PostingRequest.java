package com.wtc.wallet.application;

import java.util.List;
import java.util.UUID;

/**
 * Demande d'enregistrement d'une transaction au grand livre.
 *
 * @param reference clé d'idempotence métier, unique : un rejeu avec la même référence ne crée rien
 *     de plus (ex. {@code transfer:42:debit}, {@code adjustment:<uuid>})
 * @param createdBy identifiant public du compte à l'origine (facultatif)
 * @param relatedType type de l'objet métier lié (facultatif)
 */
public record PostingRequest(
        String reference,
        LedgerKind kind,
        String description,
        List<PostingLeg> legs,
        UUID createdBy,
        String relatedType,
        Long relatedId) {

    public PostingRequest {
        legs = List.copyOf(legs);
    }
}
