package com.wtc.wallet.infrastructure;

import com.wtc.wallet.application.Page;
import com.wtc.wallet.application.TopUpView;
import com.wtc.wallet.application.port.TopUpRepository;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTopUpRepository implements TopUpRepository {

    private static final String FROM =
            """
            FROM top_ups u
            JOIN customers c ON c.id = u.customer_id
            LEFT JOIN ledger_transactions lt ON lt.id = u.ledger_transaction_id
            """;

    private static final String WHERE =
            """
             WHERE (CAST(:status AS text) IS NULL OR u.status = :status)
               AND (CAST(:customer AS uuid) IS NULL OR c.public_id = :customer)
               AND (CAST(:from AS timestamptz) IS NULL OR u.created_at >= :from)
               AND (CAST(:to AS timestamptz) IS NULL OR u.created_at < :to)
               AND (CAST(:search AS text) IS NULL OR u.reference ILIKE :p OR u.provider_reference ILIKE :p
                    OR c.first_name ILIKE :p OR c.last_name ILIKE :p OR c.business_name ILIKE :p OR c.phone_e164 ILIKE :p)
            """;

    private final JdbcClient jdbc;

    JdbcTopUpRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Page<TopUpView> search(Filter f, int page, int size, boolean newestFirst) {
        String order = newestFirst ? "DESC" : "ASC";
        String pattern =
                f.search() == null
                        ? null
                        : "%"
                                + f.search()
                                        .replace("\\", "\\\\")
                                        .replace("%", "\\%")
                                        .replace("_", "\\_")
                                + "%";
        var rows =
                bind(
                                jdbc.sql(
                                        """
                                        SELECT u.public_id, u.reference, u.method, u.amount, u.currency_code, u.status,
                                               u.failure_reason, u.created_at,
                                               CASE WHEN u.status = 'COMPLETED' THEN COALESCE(lt.occurred_at, u.updated_at) END AS completed_at,
                                               c.public_id AS customer_public_id, c.kind, c.first_name, c.last_name, c.business_name
                                        """
                                                + FROM
                                                + WHERE
                                                + " ORDER BY u.created_at "
                                                + order
                                                + ", u.id "
                                                + order
                                                + " LIMIT :size OFFSET :offset"),
                                f,
                                pattern)
                        .param("size", size)
                        .param("offset", (long) page * size)
                        .query(
                                (rs, i) -> {
                                    Timestamp done = rs.getTimestamp("completed_at");
                                    return new TopUpView(
                                            rs.getObject("public_id", UUID.class),
                                            rs.getString("reference"),
                                            rs.getString("method"),
                                            rs.getBigDecimal("amount"),
                                            rs.getString("currency_code").strip(),
                                            rs.getString("status"),
                                            rs.getString("failure_reason"),
                                            rs.getTimestamp("created_at").toInstant(),
                                            done == null ? null : done.toInstant(),
                                            rs.getObject("customer_public_id", UUID.class),
                                            name(rs));
                                })
                        .list();
        long total =
                bind(jdbc.sql("SELECT count(*) " + FROM + WHERE), f, pattern)
                        .query(Long.class)
                        .single();
        return new Page<>(rows, total);
    }

    private static String name(java.sql.ResultSet rs) throws java.sql.SQLException {
        if ("BUSINESS".equals(rs.getString("kind"))) {
            return rs.getString("business_name");
        }
        String first = rs.getString("first_name");
        String last = rs.getString("last_name");
        return ((first == null ? "" : first) + " " + (last == null ? "" : last)).strip();
    }

    private static JdbcClient.StatementSpec bind(
            JdbcClient.StatementSpec spec, Filter f, String pattern) {
        return spec.param("status", f.status())
                .param("customer", f.customerId(), java.sql.Types.OTHER)
                .param("from", f.from() == null ? null : Timestamp.from(f.from()))
                .param("to", f.to() == null ? null : Timestamp.from(f.to()))
                .param("search", f.search())
                .param("p", pattern);
    }
}
