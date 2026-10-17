package com.wtc.identity.application.port;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public interface AccessTokenIssuer {

    /**
     * @param enrollmentOnly jeton sans aucun droit, délivré au personnel qui n'a pas encore activé
     *     son second facteur
     */
    record Claims(
            UUID userId,
            Set<String> roles,
            Set<String> permissions,
            UUID sessionId,
            boolean enrollmentOnly,
            UUID customerId) {
        public Claims {
            roles = Set.copyOf(roles);
            permissions = Set.copyOf(permissions);
        }
    }

    record Issued(String token, Instant expiresAt, long expiresInSeconds) {}

    Issued issue(Claims claims, Instant now);
}
