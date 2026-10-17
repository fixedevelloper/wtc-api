package com.wtc.transfer.infrastructure;

import com.wtc.transfer.application.port.PayoutAttemptRepository;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPayoutAttemptRepository implements PayoutAttemptRepository {

    private final JdbcClient jdbc;

    JdbcPayoutAttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Attempt> list(long transferId) {
        return jdbc.sql(
                        "SELECT id, attempt_no, outcome FROM payout_attempts WHERE transfer_id = :t"
                                + " ORDER BY attempt_no")
                .param("t", transferId)
                .query(
                        (rs, n) ->
                                new Attempt(
                                        rs.getLong("id"),
                                        rs.getInt("attempt_no"),
                                        rs.getString("outcome")))
                .list();
    }

    @Override
    public List<AttemptView> views(long transferId) {
        return jdbc.sql(
                        "SELECT attempt_no, outcome, provider_status, started_at, finished_at FROM"
                                + " payout_attempts WHERE transfer_id = :t ORDER BY attempt_no")
                .param("t", transferId)
                .query(
                        (rs, n) ->
                                new AttemptView(
                                        rs.getInt("attempt_no"),
                                        rs.getString("outcome"),
                                        rs.getString("provider_status"),
                                        rs.getTimestamp("started_at").toInstant(),
                                        rs.getTimestamp("finished_at") == null
                                                ? null
                                                : rs.getTimestamp("finished_at").toInstant()))
                .list();
    }

    @Override
    public long start(long transferId, int attemptNo) {
        return jdbc.sql(
                        "INSERT INTO payout_attempts (transfer_id, attempt_no, request_payload,"
                            + " outcome) VALUES (:t, :n, '{}'::jsonb, 'AMBIGUOUS') RETURNING id")
                .param("t", transferId)
                .param("n", attemptNo)
                .query(Long.class)
                .single();
    }

    @Override
    public void finish(
            long attemptId,
            String outcome,
            String requestJson,
            String responseJson,
            String providerStatus,
            String providerReference) {
        jdbc.sql(
                        "UPDATE payout_attempts SET outcome = :o, request_payload = CAST(:req AS"
                            + " jsonb), response_payload = CAST(:resp AS jsonb), provider_status ="
                            + " :st, provider_reference = :ref, finished_at = now() WHERE id = :id")
                .param("o", outcome)
                .param("req", jsonOrEmpty(requestJson))
                .param("resp", responseJson == null ? null : jsonOrEmpty(responseJson))
                .param("st", providerStatus)
                .param("ref", providerReference)
                .param("id", attemptId)
                .update();
    }

    private static String jsonOrEmpty(String value) {
        if (value == null || value.isBlank()) {
            return "{}";
        }
        String trimmed = value.strip();
        return trimmed.startsWith("{") || trimmed.startsWith("[") ? trimmed : "{\"raw\":true}";
    }
}
