package com.wtc.notification.infrastructure;

import com.wtc.notification.application.Channel;
import com.wtc.notification.application.NotificationMessage;
import com.wtc.notification.application.port.NotificationRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcNotificationRepository implements NotificationRepository {

    private final JdbcClient jdbc;

    JdbcNotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean enqueue(
            Channel channel,
            String recipient,
            String template,
            String payloadJson,
            String dedupeKey,
            Instant now) {
        return jdbc.sql(
                                """
                                INSERT INTO notification_outbox (channel, recipient, template, payload, dedupe_key, next_attempt_at)
                                VALUES (:channel, :recipient, :template, CAST(:payload AS jsonb), :dedupe, :now)
                                ON CONFLICT (dedupe_key) DO NOTHING
                                """)
                        .param("channel", channel.name())
                        .param("recipient", recipient)
                        .param("template", template)
                        .param("payload", payloadJson)
                        .param("dedupe", dedupeKey)
                        .param("now", Timestamp.from(now))
                        .update()
                == 1;
    }

    @Override
    public List<NotificationMessage> claimDue(int limit, Instant now, Duration visibility) {
        return jdbc.sql(
                        """
                        UPDATE notification_outbox SET attempts = attempts + 1, next_attempt_at = :later
                        WHERE id IN (SELECT id FROM notification_outbox WHERE status = 'PENDING' AND next_attempt_at <= :now
                                     ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED)
                        RETURNING id, channel, recipient, template, payload::text AS payload, attempts
                        """)
                .param("later", Timestamp.from(now.plus(visibility)))
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(
                        (rs, n) ->
                                new NotificationMessage(
                                        rs.getLong("id"),
                                        Channel.valueOf(rs.getString("channel")),
                                        rs.getString("recipient"),
                                        rs.getString("template"),
                                        rs.getString("payload"),
                                        rs.getInt("attempts")))
                .list();
    }

    @Override
    public void markSent(long id, Instant now) {
        jdbc.sql(
                        "UPDATE notification_outbox SET status = 'SENT', sent_at = :now, last_error"
                                + " = NULL WHERE id = :id")
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    @Override
    public void retryLater(long id, Instant at, String error) {
        jdbc.sql(
                        "UPDATE notification_outbox SET next_attempt_at = :at, last_error = :e"
                                + " WHERE id = :id")
                .param("at", Timestamp.from(at))
                .param("e", truncate(error))
                .param("id", id)
                .update();
    }

    @Override
    public void markFailed(long id, String error) {
        jdbc.sql("UPDATE notification_outbox SET status = 'FAILED', last_error = :e WHERE id = :id")
                .param("e", truncate(error))
                .param("id", id)
                .update();
    }

    @Override
    public void markSkipped(long id, String reason) {
        jdbc.sql(
                        "UPDATE notification_outbox SET status = 'SKIPPED', last_error = :e WHERE"
                                + " id = :id")
                .param("e", truncate(reason))
                .param("id", id)
                .update();
    }

    private static String truncate(String value) {
        return value == null ? null : value.substring(0, Math.min(value.length(), 500));
    }
}
