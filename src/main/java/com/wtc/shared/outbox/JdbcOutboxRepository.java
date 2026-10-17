package com.wtc.shared.outbox;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOutboxRepository implements OutboxRepository {

    private final JdbcClient jdbc;

    JdbcOutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enqueue(
            String aggregateType, long aggregateId, String eventType, Instant notBefore) {
        jdbc.sql(
                        "INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type,"
                            + " payload, next_attempt_at) VALUES (:a, :id, :t, '{}'::jsonb, :at)")
                .param("a", aggregateType)
                .param("id", aggregateId)
                .param("t", eventType)
                .param("at", Timestamp.from(notBefore))
                .update();
    }

    @Override
    public List<Event> claimDue(List<String> types, int limit, Instant now) {
        return jdbc.sql(
                        """
                        UPDATE outbox_events SET status = 'PROCESSING', attempts = attempts + 1, processed_at = now()
                        WHERE id IN (SELECT id FROM outbox_events WHERE status = 'PENDING' AND next_attempt_at <= :now AND event_type IN (:types)
                                     ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED)
                        RETURNING id, event_type, aggregate_id, attempts
                        """)
                .param("types", types)
                .param("limit", limit)
                .param("now", Timestamp.from(now))
                .query(
                        (rs, n) ->
                                new Event(
                                        rs.getLong("id"),
                                        rs.getString("event_type"),
                                        rs.getLong("aggregate_id"),
                                        rs.getInt("attempts")))
                .list();
    }

    @Override
    public String payloadOf(long eventId) {
        return jdbc.sql("SELECT payload::text FROM outbox_events WHERE id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();
    }

    @Override
    public void done(long eventId) {
        jdbc.sql(
                        "UPDATE outbox_events SET status = 'DONE', processed_at = now(), last_error"
                                + " = NULL WHERE id = :id")
                .param("id", eventId)
                .update();
    }

    @Override
    public void retryLater(long eventId, Instant at, String error) {
        jdbc.sql(
                        "UPDATE outbox_events SET status = 'PENDING', next_attempt_at = :at,"
                                + " last_error = :e WHERE id = :id")
                .param("at", Timestamp.from(at))
                .param("e", error)
                .param("id", eventId)
                .update();
    }

    @Override
    public void fail(long eventId, String error) {
        jdbc.sql(
                        "UPDATE outbox_events SET status = 'FAILED', processed_at = now(),"
                                + " last_error = :e WHERE id = :id")
                .param("e", error)
                .param("id", eventId)
                .update();
    }

    @Override
    public boolean pending(String aggregateType, long aggregateId, String eventType) {
        return jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM outbox_events WHERE aggregate_type = :a AND"
                            + " aggregate_id = :id AND event_type = :t AND status IN ('PENDING',"
                            + " 'PROCESSING'))")
                .param("a", aggregateType)
                .param("id", aggregateId)
                .param("t", eventType)
                .query(Boolean.class)
                .single();
    }

    @Override
    public int releaseStale(Instant olderThan) {
        return jdbc.sql(
                        "UPDATE outbox_events SET status = 'PENDING' WHERE status = 'PROCESSING'"
                                + " AND processed_at < :t")
                .param("t", Timestamp.from(olderThan))
                .update();
    }
}
