package com.wtc.identity.application.port;

import java.time.Instant;
import java.util.Optional;

public interface OneTimeTokenStore {

    enum Purpose {
        PASSWORD_RESET,
        EMAIL_VERIFICATION,
        MFA_CHALLENGE
    }

    record Token(long id, long userId, Purpose purpose, Instant expiresAt) {}

    void create(long userId, Purpose purpose, String tokenHash, Instant expiresAt);

    /** Jeton non utilisé et non expiré. */
    Optional<Token> findUsable(Purpose purpose, String tokenHash, Instant now);

    /** Marque le jeton utilisé ; faux s'il l'était déjà (un seul gagnant en cas de concurrence). */
    boolean consume(long tokenId, Instant now);

    /** Compte un essai ; faux si le plafond est dépassé (le jeton est alors invalidé). */
    boolean registerAttempt(long tokenId, int maxAttempts, Instant now);

    void invalidateAll(long userId, Purpose purpose, Instant now);
}
