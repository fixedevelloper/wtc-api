package com.wtc.backoffice.infrastructure;

import com.wtc.backoffice.application.port.WebTokenRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcWebTokenRepository implements WebTokenRepository {

    private static final Map<String, String> SORTS =
            Map.of(
                    "createdAt",
                    "w.created_at",
                    "expiresAt",
                    "w.expires_at",
                    "status",
                    "w.status",
                    "amount",
                    "w.amount");
    private static final String SELECT =
            "SELECT w.id, w.amount, c.currency_code, w.country_code, w.status, w.expires_at,"
                    + " w.used_at, w.created_at FROM web_tokens w JOIN countries c ON c.code ="
                    + " w.country_code";
    private static final RowMapper<WebTokenRecord> MAPPER =
            (rs, n) ->
                    new WebTokenRecord(
                            rs.getLong("id"),
                            rs.getBigDecimal("amount"),
                            rs.getString("currency_code"),
                            rs.getString("country_code"),
                            rs.getString("status"),
                            rs.getTimestamp("expires_at").toInstant(),
                            rs.getTimestamp("used_at") == null
                                    ? null
                                    : rs.getTimestamp("used_at").toInstant(),
                            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    JdbcWebTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> receivingCurrency(String countryCode) {
        return jdbc.sql("SELECT currency_code FROM countries WHERE code = :c AND can_receive")
                .param("c", countryCode)
                .query(String.class)
                .optional();
    }

    @Override
    public void insert(
            String tokenHash,
            BigDecimal amount,
            String countryCode,
            Instant expiresAt,
            UUID createdBy,
            Instant now) {
        jdbc.sql(
                        """
                        INSERT INTO web_tokens (token_hash, amount, country_code, expires_at, created_by, created_at)
                        VALUES (:h, :amount, :country, :exp, (SELECT id FROM users WHERE public_id = :by), :now)
                        """)
                .param("h", tokenHash)
                .param("amount", amount)
                .param("country", countryCode)
                .param("exp", Timestamp.from(expiresAt))
                .param("by", createdBy)
                .param("now", Timestamp.from(now))
                .update();
    }

    @Override
    public Optional<WebTokenRecord> findByHash(String tokenHash) {
        return jdbc.sql(SELECT + " WHERE w.token_hash = :h")
                .param("h", tokenHash)
                .query(MAPPER)
                .optional();
    }

    @Override
    public Optional<WebTokenRecord> lockByHash(String tokenHash) {
        return jdbc.sql(SELECT + " WHERE w.token_hash = :h FOR UPDATE OF w")
                .param("h", tokenHash)
                .query(MAPPER)
                .optional();
    }

    @Override
    public void markUsed(long id, String whatsappId, Instant now) {
        jdbc.sql(
                        "UPDATE web_tokens SET status = 'USED', used_at = :now, whatsapp_id = :w"
                                + " WHERE id = :id")
                .param("now", Timestamp.from(now))
                .param("w", whatsappId)
                .param("id", id)
                .update();
    }

    @Override
    public List<WebTokenRecord> page(int page, int size, String sortField, boolean ascending) {
        String column =
                SORTS.getOrDefault(
                        sortField, "w.created_at"); // liste blanche : jamais un nom libre
        return jdbc.sql(
                        SELECT
                                + " ORDER BY "
                                + column
                                + (ascending ? " ASC" : " DESC")
                                + ", w.id DESC LIMIT :size OFFSET :offset")
                .param("size", size)
                .param("offset", (long) page * size)
                .query(MAPPER)
                .list();
    }

    @Override
    public long count() {
        return jdbc.sql("SELECT count(*) FROM web_tokens").query(Long.class).single();
    }

    @Override
    public int expireDue(Instant now) {
        return jdbc.sql(
                        "UPDATE web_tokens SET status = 'EXPIRED' WHERE status = 'PENDING' AND"
                                + " expires_at <= :now")
                .param("now", Timestamp.from(now))
                .update();
    }
}
