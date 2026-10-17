package com.wtc.customer.application;

import java.util.UUID;

/**
 * Expéditeur ou bénéficiaire vu par le module transfert : identité complète et numéro de pièce
 * <b>en clair</b> (nécessaire pour déclarer la personne au fournisseur). Ne sort jamais du serveur
 * : le transfert le fige, chiffré, dans son instantané.
 */
public record TransferParty(
        long internalId, UUID publicId, String role, PartyData data, String idDocumentNumber) {

    @Override
    public String toString() {
        return "TransferParty[" + publicId + ", " + role + "]"; // jamais le numéro de pièce
    }
}
