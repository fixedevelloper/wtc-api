package com.wtc.customer.domain;

/** Masquage des numéros sensibles : seuls les 4 derniers caractères restent visibles. */
public final class Masking {

    private Masking() {}

    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String compact = value.strip();
        if (compact.length() <= 4) {
            return "****";
        }
        return "****" + compact.substring(compact.length() - 4);
    }

    /** Les 4 derniers caractères, conservés en clair pour l'affichage et l'assistance. */
    public static String last4(String value) {
        if (value == null || value.length() < 5) {
            return null; // trop court : rien de partiel à révéler
        }
        return value.substring(value.length() - 4);
    }
}
