package com.wtc.backoffice.infrastructure;

import com.wtc.backoffice.application.port.ReferenceOverviewRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReferenceOverviewRepository implements ReferenceOverviewRepository {

    private final JdbcClient jdbc;

    JdbcReferenceOverviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CountryCoverage> coverage() {
        return jdbc.sql(
                        """
                        SELECT c.code, c.name, c.currency_code,
                               c.wace_bank_payer_code IS NOT NULL AS bank_payer_code,
                               (SELECT count(*) FROM banks b
                                 WHERE b.country_code = c.code AND b.enabled) AS banks,
                               (SELECT count(*) FROM mobile_operators o
                                 WHERE o.country_code = c.code AND o.enabled) AS operators,
                               (SELECT count(*) FROM mobile_operators o
                                 WHERE o.country_code = c.code AND o.enabled
                                   AND o.wace_payer_code IS NULL) AS operators_without_code
                        FROM countries c
                        WHERE c.enabled AND c.can_receive
                        ORDER BY c.name
                        """)
                .query(
                        (rs, n) ->
                                new CountryCoverage(
                                        rs.getString("code").strip(),
                                        rs.getString("name"),
                                        rs.getString("currency_code").strip(),
                                        rs.getBoolean("bank_payer_code"),
                                        rs.getInt("banks"),
                                        rs.getInt("operators"),
                                        rs.getInt("operators_without_code")))
                .list();
    }

    @Override
    public int referenceValues() {
        return jdbc.sql("SELECT count(*) FROM reference_values WHERE enabled")
                .query(Integer.class)
                .single();
    }

    @Override
    public Optional<LastSync> lastSync() {
        return jdbc.sql(
                        "SELECT occurred_at, actor_label FROM audit_log WHERE action ="
                                + " 'REFERENCE_SYNCED' ORDER BY occurred_at DESC LIMIT 1")
                .query(
                        (rs, n) -> {
                            Timestamp at = rs.getTimestamp("occurred_at");
                            return new LastSync(at.toInstant(), rs.getString("actor_label"));
                        })
                .optional();
    }
}
