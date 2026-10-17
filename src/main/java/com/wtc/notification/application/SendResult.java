package com.wtc.notification.application;

/**
 * Issue d'un envoi : {@code SENT}, {@code RETRY} (panne passagère : on réessaie), {@code PERMANENT}
 * (échec définitif : adresse refusée, modèle inexistant, jeton invalide), {@code SKIPPED} (rien à
 * envoyer).
 */
public record SendResult(Kind kind, String detail) {

    public enum Kind {
        SENT,
        RETRY,
        PERMANENT,
        SKIPPED
    }

    public static SendResult sent() {
        return new SendResult(Kind.SENT, null);
    }

    public static SendResult retry(String detail) {
        return new SendResult(Kind.RETRY, detail);
    }

    public static SendResult permanent(String detail) {
        return new SendResult(Kind.PERMANENT, detail);
    }

    public static SendResult skipped(String detail) {
        return new SendResult(Kind.SKIPPED, detail);
    }
}
