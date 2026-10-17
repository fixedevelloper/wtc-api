package com.wtc.identity.infrastructure;

import com.wtc.identity.application.port.OneTimeTokenStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOneTimeTokenStore implements OneTimeTokenStore {

    private final JdbcClient jdbc;

    JdbcOneTimeTokenStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(long userId, Purpose purpose, String tokenHash, Instant expiresAt) {
        jdbc.sql(
                        "INSERT INTO one_time_tokens (user_id, purpose, token_hash, expires_at)"
                                + " VALUES (:u, :p, :h, :e)")
                .param("u", userId)
                .param("p", purpose.name())
                .param("h", tokenHash)
                .param("e", Timestamp.from(expiresAt))
                .update();
    }

    @Override
    public Optional<Token> findUsable(Purpose purpose, String tokenHash, Instant now) {
        return jdbc.sql(
                        """
                        SELECT id, user_id, expires_at FROM one_time_tokens
                        WHERE purpose = :p AND token_hash = :h AND used_at IS NULL AND expires_at > :now
                        """)
                .param("p", purpose.name())
                .param("h", tokenHash)
                .param("now", Timestamp.from(now))
                .query(
                        (rs, n) ->
                                new Token(
                                        rs.getLong("id"),
                                        rs.getLong("user_id"),
                                        purpose,
                                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    @Override
    public boolean consume(long tokenId, Instant now) {
        return jdbc.sql(
                                "UPDATE one_time_tokens SET used_at = :now WHERE id = :id AND"
                                        + " used_at IS NULL")
                        .param("now", Timestamp.from(now))
                        .param("id", tokenId)
                        .update()
                == 1;
    }

    @Override
    public boolean registerAttempt(long tokenId, int maxAttempts, Instant now) {
        int counted =
                jdbc.sql(
                                "UPDATE one_time_tokens SET attempts = attempts + 1 WHERE id = :id"
                                        + " AND used_at IS NULL AND attempts < :max")
                        .param("id", tokenId)
                        .param("max", maxAttempts)
                        .update();
        if (counted == 0) {
            consume(tokenId, now); // plafond atteint : le défi est brûlé
            return false;
        }
        return true;
    }

    @Override
    public void invalidateAll(long userId, Purpose purpose, Instant now) {
        jdbc.sql(
                        "UPDATE one_time_tokens SET used_at = :now WHERE user_id = :u AND purpose ="
                                + " :p AND used_at IS NULL")
                .param("now", Timestamp.from(now))
                .param("u", userId)
                .param("p", purpose.name())
                .update();
    }
}
