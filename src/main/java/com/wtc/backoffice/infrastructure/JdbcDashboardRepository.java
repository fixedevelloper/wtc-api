package com.wtc.backoffice.infrastructure;

import com.wtc.backoffice.application.port.DashboardRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDashboardRepository implements DashboardRepository {

    private final JdbcClient jdbc;

    JdbcDashboardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Month> lastMonths(String currency, Instant now) {
        // Un mois civil UTC par ligne, même sans activité ; chaque indicateur est borné au mois.
        return jdbc.sql(
                        """
                        WITH m AS (
                          SELECT generate_series(
                                   date_trunc('month', CAST(:now AS timestamptz) AT TIME ZONE 'UTC') - interval '11 months',
                                   date_trunc('month', CAST(:now AS timestamptz) AT TIME ZONE 'UTC'),
                                   interval '1 month') AS start)
                        SELECT to_char(m.start, 'YYYY-MM') AS month,
                          (SELECT COALESCE(sum(t.source_amount), 0) FROM transfers t
                            WHERE t.status = 'COMPLETED' AND t.hidden_at IS NULL
                              AND t.source_currency = :c
                              AND t.completed_at AT TIME ZONE 'UTC' >= m.start
                              AND t.completed_at AT TIME ZONE 'UTC' < m.start + interval '1 month') AS transfer_volume,
                          (SELECT count(*) FROM transfers t
                            WHERE t.status = 'COMPLETED' AND t.hidden_at IS NULL
                              AND t.source_currency = :c
                              AND t.completed_at AT TIME ZONE 'UTC' >= m.start
                              AND t.completed_at AT TIME ZONE 'UTC' < m.start + interval '1 month') AS transfer_count,
                          (SELECT COALESCE(sum(t.fee_amount), 0) FROM transfers t
                            WHERE t.status = 'COMPLETED' AND t.hidden_at IS NULL
                              AND t.source_currency = :c
                              AND t.completed_at AT TIME ZONE 'UTC' >= m.start
                              AND t.completed_at AT TIME ZONE 'UTC' < m.start + interval '1 month') AS fees,
                          (SELECT COALESCE(sum(u.amount), 0) FROM top_ups u
                            WHERE u.status = 'COMPLETED' AND u.currency_code = :c
                              AND u.created_at AT TIME ZONE 'UTC' >= m.start
                              AND u.created_at AT TIME ZONE 'UTC' < m.start + interval '1 month') AS top_up_volume,
                          (SELECT count(*) FROM top_ups u
                            WHERE u.status = 'COMPLETED' AND u.currency_code = :c
                              AND u.created_at AT TIME ZONE 'UTC' >= m.start
                              AND u.created_at AT TIME ZONE 'UTC' < m.start + interval '1 month') AS top_up_count,
                          (SELECT count(*) FROM customers c
                            WHERE c.created_at AT TIME ZONE 'UTC' >= m.start
                              AND c.created_at AT TIME ZONE 'UTC' < m.start + interval '1 month') AS new_customers
                        FROM m ORDER BY m.start
                        """)
                .param("c", currency)
                .param("now", Timestamp.from(now))
                .query(
                        (rs, i) ->
                                new Month(
                                        rs.getString("month"),
                                        rs.getBigDecimal("transfer_volume"),
                                        rs.getLong("transfer_count"),
                                        rs.getBigDecimal("fees"),
                                        rs.getBigDecimal("top_up_volume"),
                                        rs.getLong("top_up_count"),
                                        rs.getLong("new_customers")))
                .list();
    }

    @Override
    public Totals totals(String currency) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbc.sql(
                        "SELECT status, count(*) AS n FROM transfers WHERE hidden_at IS NULL"
                                + " GROUP BY status ORDER BY status")
                .query(
                        rs -> {
                            byStatus.put(rs.getString("status"), rs.getLong("n"));
                        });
        return jdbc.sql(
                        """
                        SELECT (SELECT count(*) FROM customers) AS customers,
                               (SELECT count(*) FROM customers WHERE status = 'ACTIVE') AS active_customers,
                               (SELECT COALESCE(sum(balance), 0) FROM wallets WHERE currency_code = :c) AS balance,
                               (SELECT count(*) FROM top_ups WHERE status IN ('PENDING', 'PROCESSING')) AS pending_top_ups
                        """)
                .param("c", currency)
                .query(
                        (rs, i) ->
                                new Totals(
                                        rs.getLong("customers"),
                                        rs.getLong("active_customers"),
                                        rs.getBigDecimal("balance"),
                                        rs.getLong("pending_top_ups"),
                                        byStatus))
                .single();
    }
}
