package com.wtc.identity.application.port;

import com.wtc.identity.domain.User;
import java.time.Instant;

/**
 * Remise des liens à usage unique à l'utilisateur (le canal d'envoi est l'affaire du module de
 * notification).
 */
public interface AuthNotifier {

    void passwordResetRequested(User user, String rawToken, Instant expiresAt);

    void staffInvited(User user, String rawToken, Instant expiresAt);
}
