package com.wtc.wallet.infrastructure;

import com.wtc.wallet.application.AdjustmentView;
import com.wtc.wallet.application.Page;
import com.wtc.wallet.application.port.AdjustmentRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Ajustements de solde. Les comptes (auteur, décideur) et le client sont lus par jointure SQL sur
 * leurs identifiants publics.
 */
@Repository
class JdbcAdjustmentRepository implements AdjustmentRepository {

    private static final String SELECT =
            """
            SELECT a.public_id, c.public_id AS customer_public_id, c.kind, c.first_name, c.last_name, c.business_name,
                   a.direction, a.amount, a.currency_code, a.reason, a.status, ur.email AS requested_by, ud.email AS decided_by,
                   a.decided_at, a.created_at, a.request_fingerprint
            FROM balance_adjustments a
              JOIN wallets w ON w.id = a.wallet_id
              JOIN customers c ON c.id = w.customer_id
              JOIN users ur ON ur.id = a.requested_by
              LEFT JOIN users ud ON ud.id = a.decided_by
            """;

    private static final RowMapper<AdjustmentView> VIEW =
            (rs, n) ->
                    new AdjustmentView(
                            rs.getObject("public_id", UUID.class),
                            rs.getObject("customer_public_id", UUID.class),
                            name(
                                    rs.getString("kind"),
                                    rs.getString("first_name"),
                                    rs.getString("last_name"),
                                    rs.getString("business_name")),
                            rs.getString("direction"),
                            rs.getBigDecimal("amount"),
                            rs.getString("currency_code").strip(),
                            rs.getString("reason"),
                            rs.getString("status"),
                            rs.getString("requested_by"),
                            rs.getString("decided_by"),
                            rs.getTimestamp("decided_at") == null
                                    ? null
                                    : rs.getTimestamp("decided_at").toInstant(),
                            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    JdbcAdjustmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String name(String kind, String first, String last, String business) {
        if ("BUSINESS".equals(kind) && business != null) {
            return business;
        }
        return ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
    }

    @Override
    public Optional<Found> findByIdempotency(UUID requestedBy, String key) {
        return jdbc.sql(SELECT + " WHERE ur.public_id = :by AND a.idempotency_key = :key")
                .param("by", requestedBy)
                .param("key", key)
                .query(
                        (rs, n) ->
                                new Found(VIEW.mapRow(rs, n), rs.getString("request_fingerprint")))
                .optional();
    }

    @Override
    public UUID insert(
            long walletId,
            String direction,
            BigDecimal amount,
            String currency,
            String reason,
            UUID requestedBy,
            String key,
            String fingerprint) {
        return jdbc.sql(
                        """
                        INSERT INTO balance_adjustments (wallet_id, direction, amount, currency_code, reason, requested_by, idempotency_key, request_fingerprint)
                        VALUES (:w, :d, :a, :c, :r, (SELECT id FROM users WHERE public_id = :by), :key, :fp) RETURNING public_id
                        """)
                .param("w", walletId)
                .param("d", direction)
                .param("a", amount)
                .param("c", currency)
                .param("r", reason)
                .param("by", requestedBy)
                .param("key", key)
                .param("fp", fingerprint)
                .query(UUID.class)
                .single();
    }

    @Override
    public Optional<AdjustmentView> find(UUID publicId) {
        return jdbc.sql(SELECT + " WHERE a.public_id = :id")
                .param("id", publicId)
                .query(VIEW)
                .optional();
    }

    @Override
    public Optional<Pending> lock(UUID publicId) {
        return jdbc.sql(
                        """
                        SELECT a.id, a.public_id, a.wallet_id, a.direction, a.amount, a.currency_code, a.status, ur.public_id AS requested_by
                        FROM balance_adjustments a JOIN users ur ON ur.id = a.requested_by
                        WHERE a.public_id = :id FOR UPDATE OF a
                        """)
                .param("id", publicId)
                .query(
                        (rs, n) ->
                                new Pending(
                                        rs.getLong("id"),
                                        rs.getObject("public_id", UUID.class),
                                        rs.getLong("wallet_id"),
                                        rs.getString("direction"),
                                        rs.getBigDecimal("amount"),
                                        rs.getString("currency_code").strip(),
                                        rs.getString("status"),
                                        rs.getObject("requested_by", UUID.class)))
                .optional();
    }

    @Override
    public void markApproved(long id, UUID decidedBy, long ledgerTransactionId, String note) {
        jdbc.sql(
                        "UPDATE balance_adjustments SET status = 'APPROVED', decided_by = (SELECT"
                            + " id FROM users WHERE public_id = :by), decided_at = now(),"
                            + " ledger_transaction_id = :tx, decision_note = :note WHERE id = :id")
                .param("by", decidedBy)
                .param("tx", ledgerTransactionId)
                .param("note", note)
                .param("id", id)
                .update();
    }

    @Override
    public void markRejected(long id, UUID decidedBy, String note) {
        jdbc.sql(
                        "UPDATE balance_adjustments SET status = 'REJECTED', decided_by = (SELECT"
                                + " id FROM users WHERE public_id = :by), decided_at = now(),"
                                + " decision_note = :note WHERE id = :id")
                .param("by", decidedBy)
                .param("note", note)
                .param("id", id)
                .update();
    }

    @Override
    public Page<AdjustmentView> list(
            String search, String status, int page, int size, boolean newestFirst) {
        String order = newestFirst ? "DESC" : "ASC";
        String where =
                " WHERE (CAST(:search AS text) IS NULL OR c.first_name ILIKE :p OR c.last_name"
                        + " ILIKE :p OR c.business_name ILIKE :p OR a.reason ILIKE :p)"
                        + " AND (CAST(:status AS text) IS NULL OR a.status = :status)";
        String pattern =
                search == null
                        ? null
                        : "%"
                                + search.replace("\\", "\\\\")
                                        .replace("%", "\\%")
                                        .replace("_", "\\_")
                                + "%";
        List<AdjustmentView> rows =
                jdbc.sql(
                                SELECT
                                        + where
                                        + " ORDER BY a.created_at "
                                        + order
                                        + ", a.id "
                                        + order
                                        + " LIMIT :size OFFSET :offset")
                        .param("search", search)
                        .param("status", status)
                        .param("p", pattern)
                        .param("size", size)
                        .param("offset", (long) page * size)
                        .query(VIEW)
                        .list();
        long total =
                jdbc.sql(
                                "SELECT count(*) FROM balance_adjustments a JOIN wallets w ON w.id"
                                        + " = a.wallet_id JOIN customers c ON c.id = w.customer_id"
                                        + where)
                        .param("search", search)
                        .param("status", status)
                        .param("p", pattern)
                        .query(Long.class)
                        .single();
        return new Page<>(rows, total);
    }
}
