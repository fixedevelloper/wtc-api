package com.wtc.identity.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Refresh tokens opaques à rotation. Seule l'empreinte SHA-256 est conservée. */
public interface RefreshTokenStore {

    record Stored(
            long id, long userId, UUID familyId, Instant expiresAt, Instant familyStartedAt) {}

    enum RotationOutcome {
        ROTATED,
        /** Le jeton présenté avait déjà été utilisé ou révoqué : toute la famille est révoquée. */
        REUSED,
        /** Inconnu ou expiré. */
        INVALID
    }

    record Rotation(RotationOutcome outcome, Stored previous) {}

    void insert(
            long userId,
            UUID familyId,
            String tokenHash,
            Instant issuedAt,
            Instant expiresAt,
            String userAgent,
            String ip);

    /**
     * Remplace atomiquement le jeton présenté par un nouveau. Si le jeton a déjà servi, la famille
     * entière est révoquée et la révocation est conservée.
     */
    Rotation rotate(
            String presentedHash,
            String newHash,
            Instant now,
            java.util.function.Function<Stored, Instant> newExpiry,
            String userAgent,
            String ip);

    Optional<Stored> find(String tokenHash);

    void revokeFamily(UUID familyId, Instant now);

    void revokeAllOfUser(long userId, Instant now);

    /**
     * Révoque toutes les sessions de l'utilisateur sauf une (la session courante lors d'un
     * changement de mot de passe).
     */
    void revokeAllOfUserExcept(long userId, UUID keepFamilyId, Instant now);
}
