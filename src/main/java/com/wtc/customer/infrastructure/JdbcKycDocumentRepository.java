package com.wtc.customer.infrastructure;

import com.wtc.customer.application.port.KycDocumentRepository;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcKycDocumentRepository implements KycDocumentRepository {

    private static final String SELECT =
            "SELECT id, public_id, customer_id, document_type, side, storage_key, status,"
                    + " expires_on, uploaded_at, rejection_reason FROM kyc_documents";

    private static final RowMapper<DocumentRecord> MAPPER =
            (rs, n) ->
                    new DocumentRecord(
                            rs.getLong("id"),
                            rs.getObject("public_id", UUID.class),
                            rs.getLong("customer_id"),
                            rs.getString("document_type"),
                            rs.getString("side"),
                            rs.getString("storage_key"),
                            rs.getString("status"),
                            rs.getObject("expires_on", LocalDate.class),
                            rs.getTimestamp("uploaded_at").toInstant(),
                            rs.getString("rejection_reason"));

    private static final RowMapper<ExpiryCase> EXPIRY =
            (rs, n) ->
                    new ExpiryCase(
                            rs.getLong("id"),
                            rs.getLong("customer_id"),
                            rs.getObject("customer_public_id", UUID.class),
                            rs.getString("document_type"),
                            rs.getObject("expires_on", LocalDate.class));

    private final JdbcClient jdbc;

    JdbcKycDocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(NewDocument d) {
        jdbc.sql(
                        """
                        INSERT INTO kyc_documents (public_id, customer_id, document_type, side, storage_key, content_type, size_bytes, sha256, expires_on)
                        VALUES (:publicId, :customerId, :type, :side, :key, :contentType, :size, :sha, :expires)
                        """)
                .param("publicId", d.publicId())
                .param("customerId", d.customerId())
                .param("type", d.documentType())
                .param("side", d.side())
                .param("key", d.storageKey())
                .param("contentType", d.contentType())
                .param("size", d.sizeBytes())
                .param("sha", d.sha256())
                .param("expires", d.expiresOn() == null ? null : Date.valueOf(d.expiresOn()))
                .update();
    }

    @Override
    public List<ExpiryCase> expireApprovedBefore(LocalDate today) {
        return jdbc.sql(
                        """
                        WITH expired AS (
                            UPDATE kyc_documents SET status = 'EXPIRED'
                            WHERE status = 'APPROVED' AND expires_on < :today
                            RETURNING id, customer_id, document_type, expires_on)
                        SELECT e.id, e.customer_id, c.public_id AS customer_public_id, e.document_type, e.expires_on
                        FROM expired e JOIN customers c ON c.id = e.customer_id ORDER BY e.id
                        """)
                .param("today", Date.valueOf(today))
                .query(EXPIRY)
                .list();
    }

    @Override
    public List<ExpiryCase> expiringWithoutReminder(LocalDate today, LocalDate until) {
        return jdbc.sql(
                        """
                        SELECT d.id, d.customer_id, c.public_id AS customer_public_id, d.document_type, d.expires_on
                        FROM kyc_documents d JOIN customers c ON c.id = d.customer_id
                        WHERE d.status = 'APPROVED' AND d.expires_on BETWEEN :today AND :until
                          AND NOT EXISTS (SELECT 1 FROM outbox_events e WHERE e.event_type = 'kyc.expiring'
                                          AND e.aggregate_type = 'KYC_DOCUMENT' AND e.aggregate_id = d.id)
                        ORDER BY d.id
                        """)
                .param("today", Date.valueOf(today))
                .param("until", Date.valueOf(until))
                .query(EXPIRY)
                .list();
    }

    @Override
    public List<DocumentRecord> list(long customerId) {
        return jdbc.sql(SELECT + " WHERE customer_id = :c ORDER BY uploaded_at DESC, id DESC")
                .param("c", customerId)
                .query(MAPPER)
                .list();
    }

    @Override
    public Optional<DocumentRecord> find(long customerId, UUID publicId) {
        return jdbc.sql(SELECT + " WHERE customer_id = :c AND public_id = :id")
                .param("c", customerId)
                .param("id", publicId)
                .query(MAPPER)
                .optional();
    }

    @Override
    public void review(
            long id, String status, UUID reviewerId, String rejectionReason, Instant now) {
        // Le relecteur est retrouvé par son identifiant public : c'est la seule lecture de la table
        // des comptes par ce module.
        jdbc.sql(
                        """
                        UPDATE kyc_documents SET status = :status, reviewed_at = :now, rejection_reason = :reason,
                            reviewed_by = (SELECT id FROM users WHERE public_id = :reviewer)
                        WHERE id = :id
                        """)
                .param("status", status)
                .param("now", Timestamp.from(now))
                .param("reason", rejectionReason)
                .param("reviewer", reviewerId)
                .param("id", id)
                .update();
    }
}
