package com.wtc.customer.application;

/**
 * Destination de paiement saisie par le client ; {@code accountNumber} est en clair ici et chiffré
 * au stockage.
 */
public record DestinationData(
        String kind,
        String countryCode,
        Long bankId,
        Long mobileOperatorId,
        String accountHolderName,
        String accountNumber,
        String swiftCode,
        String ifscCode,
        String routingNumber,
        boolean isDefault) {

    @Override
    public String toString() {
        return "DestinationData[kind="
                + kind
                + ", countryCode="
                + countryCode
                + ", accountNumber=***]"; // jamais le numéro
    }
}
