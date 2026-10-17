package com.wtc.transfer.domain;

/**
 * Type de transfert selon la nature de l'expéditeur et du bénéficiaire : P = particulier, B =
 * entreprise.
 */
public final class PartyTypePair {

    private PartyTypePair() {}

    public static String of(String senderKind, String beneficiaryKind) {
        return letter(senderKind) + "2" + letter(beneficiaryKind);
    }

    private static String letter(String kind) {
        return "BUSINESS".equals(kind) ? "B" : "P";
    }
}
