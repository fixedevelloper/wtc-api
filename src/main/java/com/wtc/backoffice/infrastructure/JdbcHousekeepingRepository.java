package com.wtc.backoffice.infrastructure;

import com.wtc.backoffice.application.port.HousekeepingRepository;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcHousekeepingRepository implements HousekeepingRepository {

    private final JdbcClient jdbc;

    JdbcHousekeepingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int purgeRefreshTokens(Instant cutoff) {
        // Les jetons périmés se référencent entre eux (rotation) : on coupe d'abord ces liens, puis
        // on supprime ceux
        // que plus aucun jeton conservé ne référence.
        jdbc.sql(
                        "UPDATE refresh_tokens SET replaced_by = NULL WHERE expires_at < :c AND"
                                + " replaced_by IS NOT NULL")
                .param("c", Timestamp.from(cutoff))
                .update();
        return jdbc.sql(
                        """
                        DELETE FROM refresh_tokens r WHERE r.expires_at < :c
                          AND NOT EXISTS (SELECT 1 FROM refresh_tokens x WHERE x.replaced_by = r.id)
                        """)
                .param("c", Timestamp.from(cutoff))
                .update();
    }

    @Override
    public int purgeOneTimeTokens(Instant cutoff) {
        return jdbc.sql("DELETE FROM one_time_tokens WHERE expires_at < :c")
                .param("c", Timestamp.from(cutoff))
                .update();
    }

    @Override
    public int purgeProcessedEvents(Instant cutoff) {
        return jdbc.sql("DELETE FROM outbox_events WHERE status = 'DONE' AND processed_at < :c")
                .param("c", Timestamp.from(cutoff))
                .update();
    }

    @Override
    public int purgeDeliveredNotifications(Instant cutoff) {
        return jdbc.sql(
                        "DELETE FROM notification_outbox WHERE status IN ('SENT', 'SKIPPED') AND"
                                + " created_at < :c")
                .param("c", Timestamp.from(cutoff))
                .update();
    }
}
