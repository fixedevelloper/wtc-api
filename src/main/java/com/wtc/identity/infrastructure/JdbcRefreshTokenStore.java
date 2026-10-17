package com.wtc.identity.infrastructure;

import com.wtc.identity.application.port.RefreshTokenStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcRefreshTokenStore implements RefreshTokenStore {

    private final JdbcClient jdbc;

    JdbcRefreshTokenStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(
            long userId,
            UUID familyId,
            String tokenHash,
            Instant issuedAt,
            Instant expiresAt,
            String userAgent,
            String ip) {
        jdbc.sql(
                        """
                        INSERT INTO refresh_tokens (user_id, family_id, token_hash, issued_at, expires_at, user_agent, ip_address)
                        VALUES (:userId, :family, :hash, :issued, :expires, :ua, CAST(:ip AS inet))
                        """)
                .param("userId", userId)
                .param("family", familyId)
                .param("hash", tokenHash)
                .param("issued", Timestamp.from(issuedAt))
                .param("expires", Timestamp.from(expiresAt))
                .param("ua", userAgent)
                .param("ip", ip)
                .update();
    }

    @Override
    public Optional<Stored> find(String tokenHash) {
        return jdbc.sql(
                        """
                        SELECT t.id, t.user_id, t.family_id, t.expires_at,
                               (SELECT min(f.issued_at) FROM refresh_tokens f WHERE f.family_id = t.family_id) AS family_started_at
                        FROM refresh_tokens t WHERE t.token_hash = :hash
                        """)
                .param("hash", tokenHash)
                .query(
                        (rs, n) ->
                                new Stored(
                                        rs.getLong("id"),
                                        rs.getLong("user_id"),
                                        rs.getObject("family_id", UUID.class),
                                        rs.getTimestamp("expires_at").toInstant(),
                                        rs.getTimestamp("family_started_at").toInstant()))
                .optional();
    }

    @Override
    @Transactional
    public Rotation rotate(
            String presentedHash,
            String newHash,
            Instant now,
            Function<Stored, Instant> newExpiry,
            String userAgent,
            String ip) {
        // Verrou de ligne : deux rotations simultanées du même jeton se sérialisent, la seconde
        // voit « déjà utilisé ».
        record State(boolean revoked, boolean rotated) {}
        Optional<State> state =
                jdbc.sql(
                                "SELECT revoked_at IS NOT NULL AS revoked, replaced_by IS NOT NULL"
                                    + " AS rotated FROM refresh_tokens WHERE token_hash = :hash FOR"
                                    + " UPDATE")
                        .param("hash", presentedHash)
                        .query(
                                (rs, n) ->
                                        new State(
                                                rs.getBoolean("revoked"), rs.getBoolean("rotated")))
                        .optional();
        Optional<Stored> stored = find(presentedHash);
        if (state.isEmpty() || stored.isEmpty()) {
            return new Rotation(RotationOutcome.INVALID, null);
        }
        Stored token = stored.get();
        if (state.get().rotated()) {
            // Ce jeton a déjà été échangé contre un autre : quelqu'un en détient une copie. On
            // coupe toute la famille.
            revokeFamily(token.familyId(), now);
            return new Rotation(RotationOutcome.REUSED, token);
        }
        if (state.get().revoked()) {
            return new Rotation(
                    RotationOutcome.INVALID,
                    token); // déconnexion ou révocation par un administrateur
        }
        if (!token.expiresAt().isAfter(now)) {
            return new Rotation(RotationOutcome.INVALID, token);
        }
        Instant expiry = newExpiry.apply(token);
        if (expiry.isBefore(
                now.plusSeconds(
                        1))) { // moins d'une seconde à vivre (et marge pour la précision de la
            // base)
            // Âge maximal de la session atteint : plus de renouvellement, il faut se reconnecter.
            revokeFamily(token.familyId(), now);
            return new Rotation(RotationOutcome.INVALID, token);
        }
        insert(token.userId(), token.familyId(), newHash, now, expiry, userAgent, ip);
        jdbc.sql(
                        """
                        UPDATE refresh_tokens SET revoked_at = :now,
                               replaced_by = (SELECT id FROM refresh_tokens WHERE token_hash = :newHash)
                        WHERE id = :id
                        """)
                .param("now", Timestamp.from(now))
                .param("newHash", newHash)
                .param("id", token.id())
                .update();
        return new Rotation(RotationOutcome.ROTATED, token);
    }

    @Override
    public void revokeFamily(UUID familyId, Instant now) {
        jdbc.sql(
                        "UPDATE refresh_tokens SET revoked_at = :now WHERE family_id = :family AND"
                                + " revoked_at IS NULL")
                .param("now", Timestamp.from(now))
                .param("family", familyId)
                .update();
    }

    @Override
    public void revokeAllOfUserExcept(long userId, UUID keepFamilyId, Instant now) {
        jdbc.sql(
                        "UPDATE refresh_tokens SET revoked_at = :now WHERE user_id = :userId AND"
                                + " family_id <> :keep AND revoked_at IS NULL")
                .param("now", Timestamp.from(now))
                .param("userId", userId)
                .param("keep", keepFamilyId)
                .update();
    }

    @Override
    public void revokeAllOfUser(long userId, Instant now) {
        jdbc.sql(
                        "UPDATE refresh_tokens SET revoked_at = :now WHERE user_id = :userId AND"
                                + " revoked_at IS NULL")
                .param("now", Timestamp.from(now))
                .param("userId", userId)
                .update();
    }
}
