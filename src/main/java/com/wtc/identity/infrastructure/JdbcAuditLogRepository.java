package com.wtc.identity.infrastructure;

import com.wtc.identity.application.port.AuditLogRepository;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAuditLogRepository implements AuditLogRepository {

    // Filtres facultatifs : chaque paramètre nul désactive son critère (aucune concaténation de
    // valeurs dans le SQL).
    private static final String WHERE =
            """
            WHERE (CAST(:entityType AS text) IS NULL OR entity_type = :entityType)
              AND (CAST(:entityId AS text) IS NULL OR entity_id = :entityId)
              AND (CAST(:actor AS text) IS NULL OR actor_label ILIKE '%' || :actor || '%')
              AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= :from)
              AND (CAST(:to AS timestamptz) IS NULL OR occurred_at < :to)
            """;

    private final JdbcClient jdbc;

    JdbcAuditLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public EntryPage search(Filter filter, int page, int size, boolean newestFirst) {
        String order = newestFirst ? "DESC" : "ASC";
        List<Entry> content =
                jdbc.sql(
                                "SELECT id, occurred_at, actor_label, actor_type, action,"
                                    + " entity_type, entity_id, before_state::text AS before_state,"
                                    + " after_state::text AS after_state, host(ip_address) AS ip,"
                                    + " request_id FROM audit_log "
                                        + WHERE
                                        + " ORDER BY occurred_at "
                                        + order
                                        + ", id "
                                        + order
                                        + " LIMIT :size OFFSET :offset")
                        .param("entityType", filter.entityType())
                        .param("entityId", filter.entityId())
                        .param("actor", filter.actor())
                        .param("from", filter.from() == null ? null : Timestamp.from(filter.from()))
                        .param("to", filter.to() == null ? null : Timestamp.from(filter.to()))
                        .param("size", size)
                        .param("offset", (long) page * size)
                        .query(
                                (rs, n) ->
                                        new Entry(
                                                rs.getLong("id"),
                                                rs.getTimestamp("occurred_at").toInstant(),
                                                rs.getString("actor_label"),
                                                rs.getString("actor_type"),
                                                rs.getString("action"),
                                                rs.getString("entity_type"),
                                                rs.getString("entity_id"),
                                                rs.getString("before_state"),
                                                rs.getString("after_state"),
                                                rs.getString("ip"),
                                                rs.getString("request_id")))
                        .list();
        long total =
                jdbc.sql("SELECT count(*) FROM audit_log " + WHERE)
                        .param("entityType", filter.entityType())
                        .param("entityId", filter.entityId())
                        .param("actor", filter.actor())
                        .param("from", filter.from() == null ? null : Timestamp.from(filter.from()))
                        .param("to", filter.to() == null ? null : Timestamp.from(filter.to()))
                        .query(Long.class)
                        .single();
        return new EntryPage(content, total);
    }
}
