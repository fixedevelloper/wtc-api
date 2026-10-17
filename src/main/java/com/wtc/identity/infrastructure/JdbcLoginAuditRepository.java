package com.wtc.identity.infrastructure;

import com.wtc.identity.application.port.LoginAuditRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLoginAuditRepository implements LoginAuditRepository {

    private final JdbcClient jdbc;

    JdbcLoginAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(
            Long userId,
            String emailAttempted,
            Outcome outcome,
            String ip,
            String userAgent,
            Instant now) {
        jdbc.sql(
                        """
                        INSERT INTO login_audit (user_id, email_attempted, outcome, ip_address, user_agent, occurred_at)
                        VALUES (:userId, :email, :outcome, CAST(:ip AS inet), :ua, :now)
                        """)
                .param("userId", userId)
                .param("email", emailAttempted)
                .param("outcome", outcome.name())
                .param("ip", ip)
                .param("ua", userAgent)
                .param("now", Timestamp.from(now))
                .update();
    }

    @Override
    public EntryPage list(int page, int size, boolean newestFirst) {
        String order = newestFirst ? "DESC" : "ASC";
        List<Entry> content =
                jdbc.sql(
                                "SELECT id, email_attempted, outcome, host(ip_address) AS ip,"
                                        + " user_agent, occurred_at FROM login_audit ORDER BY"
                                        + " occurred_at "
                                        + order
                                        + ", id "
                                        + order
                                        + " LIMIT :size OFFSET :offset")
                        .param("size", size)
                        .param("offset", (long) page * size)
                        .query(
                                (rs, n) ->
                                        new Entry(
                                                rs.getLong("id"),
                                                rs.getString("email_attempted"),
                                                Outcome.valueOf(rs.getString("outcome")),
                                                rs.getString("ip"),
                                                rs.getString("user_agent"),
                                                rs.getTimestamp("occurred_at").toInstant()))
                        .list();
        long total = jdbc.sql("SELECT count(*) FROM login_audit").query(Long.class).single();
        return new EntryPage(content, total);
    }
}
