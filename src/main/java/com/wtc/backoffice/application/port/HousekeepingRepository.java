package com.wtc.backoffice.application.port;

import java.time.Instant;

/**
 * Purges de données techniques périmées. Ne touche jamais au grand livre, à l'audit ni aux
 * transferts.
 */
public interface HousekeepingRepository {

    /**
     * Jetons de rafraîchissement expirés avant {@code cutoff} (la chaîne de rotation est coupée
     * avant suppression).
     */
    int purgeRefreshTokens(Instant cutoff);

    /** Jetons à usage unique (réinitialisation, MFA) expirés avant {@code cutoff}. */
    int purgeOneTimeTokens(Instant cutoff);

    /** Événements d'envoi traités avant {@code cutoff}. */
    int purgeProcessedEvents(Instant cutoff);

    /** Notifications envoyées ou ignorées avant {@code cutoff} (les échecs restent pour examen). */
    int purgeDeliveredNotifications(Instant cutoff);
}
