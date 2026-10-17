package com.wtc.customer.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycDocumentRepository {

    record DocumentRecord(
            long id,
            UUID publicId,
            long customerId,
            String documentType,
            String side,
            String storageKey,
            String status,
            LocalDate expiresOn,
            Instant uploadedAt,
            String rejectionReason) {}

    record NewDocument(
            UUID publicId,
            long customerId,
            String documentType,
            String side,
            String storageKey,
            String contentType,
            long sizeBytes,
            String sha256,
            LocalDate expiresOn) {}

    /** Un document dont l'échéance intéresse un client (échu ou bientôt échu). */
    record ExpiryCase(
            long documentId,
            long customerId,
            UUID customerPublicId,
            String documentType,
            LocalDate expiresOn) {}

    /**
     * Passe en EXPIRED les documents approuvés dont la date d'échéance est antérieure à {@code
     * today} ; retourne ceux-ci.
     */
    List<ExpiryCase> expireApprovedBefore(LocalDate today);

    /**
     * Documents approuvés qui expirent dans {@code [today, until]} et dont personne n'a encore été
     * prévenu.
     */
    List<ExpiryCase> expiringWithoutReminder(LocalDate today, LocalDate until);

    void insert(NewDocument document);

    List<DocumentRecord> list(long customerId);

    Optional<DocumentRecord> find(long customerId, UUID publicId);

    /**
     * Enregistre la décision ; {@code reviewerId} est l'identifiant public du compte qui examine.
     */
    void review(long id, String status, UUID reviewerId, String rejectionReason, Instant now);
}
