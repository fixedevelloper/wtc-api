package com.wtc.identity.application.port;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepository {

    record Entry(
            long id,
            Instant occurredAt,
            String actor,
            String actorType,
            String action,
            String entityType,
            String entityId,
            String beforeJson,
            String afterJson,
            String ipAddress,
            String requestId) {}

    record Filter(String entityType, String entityId, String actor, Instant from, Instant to) {}

    record EntryPage(List<Entry> content, long total) {
        public EntryPage {
            content = List.copyOf(content);
        }
    }

    EntryPage search(Filter filter, int page, int size, boolean newestFirst);
}
