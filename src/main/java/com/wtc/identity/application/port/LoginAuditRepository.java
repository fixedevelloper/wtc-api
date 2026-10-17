package com.wtc.identity.application.port;

import java.time.Instant;
import java.util.List;

public interface LoginAuditRepository {

    enum Outcome {
        SUCCESS,
        BAD_CREDENTIALS,
        LOCKED,
        MFA_REQUIRED,
        MFA_FAILED,
        SUSPENDED
    }

    record Entry(
            long id,
            String email,
            Outcome outcome,
            String ipAddress,
            String userAgent,
            Instant occurredAt) {}

    record EntryPage(List<Entry> content, long total) {
        public EntryPage {
            content = List.copyOf(content);
        }
    }

    void record(
            Long userId,
            String emailAttempted,
            Outcome outcome,
            String ip,
            String userAgent,
            Instant now);

    EntryPage list(int page, int size, boolean newestFirst);
}
