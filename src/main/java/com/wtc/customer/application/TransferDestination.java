package com.wtc.customer.application;

import java.util.UUID;

/**
 * Destination de paiement avec son numéro en clair, pour l'envoi (même réserve que {@link
 * TransferParty}).
 */
public record TransferDestination(
        long internalId,
        UUID publicId,
        long partyInternalId,
        String kind,
        String countryCode,
        Long bankId,
        Long mobileOperatorId,
        String accountHolderName,
        String accountNumber,
        String accountNumberMasked,
        String swiftCode,
        String ifscCode,
        String routingNumber) {

    @Override
    public String toString() {
        return "TransferDestination[" + publicId + ", " + kind + "]"; // jamais le numéro de compte
    }
}
