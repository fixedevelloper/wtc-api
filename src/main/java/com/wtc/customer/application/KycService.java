package com.wtc.customer.application;

import com.wtc.customer.application.port.CustomerRepository;
import com.wtc.customer.application.port.CustomerRepository.CustomerRecord;
import com.wtc.customer.application.port.KycDocumentRepository;
import com.wtc.customer.application.port.KycDocumentRepository.DocumentRecord;
import com.wtc.customer.application.port.KycDocumentRepository.NewDocument;
import com.wtc.customer.domain.KycDocumentStatus;
import com.wtc.customer.domain.KycDocumentType;
import com.wtc.customer.domain.KycPolicy;
import com.wtc.customer.domain.KycStatus;
import com.wtc.shared.audit.AuditTrail;
import com.wtc.shared.config.WtcProperties;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import com.wtc.shared.outbox.EventPublisher;
import com.wtc.shared.ratelimit.RateLimiter;
import com.wtc.shared.ratelimit.RateLimiter.Limit;
import com.wtc.shared.security.Tokens;
import com.wtc.shared.storage.FileTypes;
import com.wtc.shared.storage.FileTypes.Kind;
import com.wtc.shared.storage.ObjectStorage;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pièces justificatives (KYC). Les fichiers vont dans un stockage privé et ne sont remis que par
 * lien signé de courte durée ; le type réel est reconnu par la signature du fichier, jamais d'après
 * ce qu'annonce le client.
 */
@Service
public class KycService {

    public static final int MAX_BYTES = 8 * 1024 * 1024;
    static final int MAX_DOCUMENTS = 30;
    private static final Duration LINK_TTL = Duration.ofMinutes(5);
    private static final Set<String> SIDES = Set.of("FRONT", "BACK", "SINGLE");

    private final KycDocumentRepository documents;
    private final CustomerRepository customers;
    private final CustomerSupport support;
    private final ObjectStorage storage;
    private final RateLimiter rateLimiter;
    private final AuditTrail audit;
    private final EventPublisher events;
    private final Clock clock;
    private final int uploadsPerHour;

    KycService(
            KycDocumentRepository documents,
            CustomerRepository customers,
            CustomerSupport support,
            ObjectStorage storage,
            RateLimiter rateLimiter,
            AuditTrail audit,
            EventPublisher events,
            Clock clock,
            WtcProperties properties) {
        this.documents = documents;
        this.customers = customers;
        this.support = support;
        this.storage = storage;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.uploadsPerHour = properties.rateLimits().sensitiveActionsPerUserPerHour();
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public KycOverview overview(UUID customerId) {
        return overviewOf(support.load(customerId));
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public KycDocumentView document(UUID customerId, UUID documentId) {
        CustomerRecord customer = support.load(customerId);
        return view(find(customer.id(), documentId));
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public KycDocumentView upload(
            UUID customerId,
            UUID actorUserId,
            String documentType,
            String side,
            LocalDate expiresOn,
            byte[] content) {
        CustomerRecord customer = support.loadActive(customerId);
        rateLimiter.check(
                "kyc-upload:" + customer.id(), new Limit(uploadsPerHour, Duration.ofHours(1)));
        KycDocumentType type = parseType(documentType);
        String documentSide = side == null || side.isBlank() ? "SINGLE" : side;
        if (!SIDES.contains(documentSide)) {
            throw invalid("side", "Invalid", "FRONT, BACK ou SINGLE.");
        }
        if (content == null || content.length == 0) {
            throw invalid("file", "Required", "Le fichier est vide.");
        }
        if (content.length > MAX_BYTES) {
            throw new DomainException(
                    ErrorCode.PAYLOAD_TOO_LARGE, "Fichier trop volumineux (8 Mio au maximum).");
        }
        Kind kind =
                FileTypes.detect(content)
                        .filter(k -> !type.imageOnly() || k.isImage())
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.UNSUPPORTED_FILE_TYPE,
                                                type.imageOnly()
                                                        ? "Une image JPEG ou PNG est attendue."
                                                        : "Formats acceptés : JPEG, PNG ou PDF."));
        LocalDate today = today();
        if (expiresOn != null && expiresOn.isBefore(today)) {
            throw invalid("expiresOn", "InPast", "Ce document est déjà expiré.");
        }
        if (documents.list(customer.id()).size() >= MAX_DOCUMENTS) {
            throw invalid("file", "TooMany", "Trop de documents déposés : contactez le support.");
        }
        UUID publicId = UUID.randomUUID();
        String key = "kyc/" + customerId + "/" + publicId;
        storage.put(key, content, kind.mediaType());
        documents.insert(
                new NewDocument(
                        publicId,
                        customer.id(),
                        type.name(),
                        documentSide,
                        key,
                        kind.mediaType(),
                        content.length,
                        Tokens.sha256Bytes(content),
                        expiresOn));
        refreshStatus(customer);
        audit.recordAs(
                actorUserId,
                "KYC_DOCUMENT_UPLOADED",
                "KYC_DOCUMENT",
                publicId.toString(),
                null,
                "{\"documentType\":\"" + type.name() + "\"}");
        return view(find(customer.id(), publicId));
    }

    // ------------------------------------------------------------------ administration

    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    @Transactional(readOnly = true)
    public KycOverview adminOverview(UUID customerId) {
        return overviewOf(support.load(customerId));
    }

    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    @Transactional
    public KycDocumentView review(
            UUID reviewerId, UUID customerId, UUID documentId, boolean approve, String reason) {
        CustomerRecord customer = support.load(customerId);
        DocumentRecord doc = find(customer.id(), documentId);
        if (!KycDocumentStatus.PENDING.name().equals(doc.status())) {
            throw new DomainException(
                    ErrorCode.KYC_DOCUMENT_ALREADY_REVIEWED, "Ce document a déjà été examiné.");
        }
        if (!approve && (reason == null || reason.isBlank())) {
            throw invalid(
                    "reason", "Required", "Un motif est obligatoire pour refuser un document.");
        }
        KycDocumentStatus decision =
                approve ? KycDocumentStatus.APPROVED : KycDocumentStatus.REJECTED;
        documents.review(
                doc.id(),
                decision.name(),
                reviewerId,
                approve ? null : reason.strip(),
                clock.instant());
        refreshStatus(customer);
        events.publish(
                "CUSTOMER",
                customer.id(),
                "kyc.reviewed",
                java.util.Map.of(
                        "customerId", customerId.toString(),
                        "decision", decision.name(),
                        "documentType", doc.documentType(),
                        "reason", approve ? "" : reason.strip()));
        audit.recordAs(
                reviewerId,
                "KYC_DOCUMENT_" + decision.name(),
                "KYC_DOCUMENT",
                documentId.toString(),
                null,
                null);
        return view(find(customer.id(), documentId));
    }

    // ------------------------------------------------------------------ interne

    private void refreshStatus(CustomerRecord customer) {
        KycStatus status = KycPolicy.statusOf(policyDocs(documents.list(customer.id())), today());
        customers.setKycStatus(customer.id(), status.name());
    }

    private KycOverview overviewOf(CustomerRecord customer) {
        List<DocumentRecord> docs = documents.list(customer.id());
        return new KycOverview(
                KycPolicy.statusOf(policyDocs(docs), today()).name(),
                docs.stream().map(this::view).toList());
    }

    static List<KycPolicy.Doc> policyDocs(List<DocumentRecord> docs) {
        return docs.stream()
                .map(
                        d ->
                                new KycPolicy.Doc(
                                        KycDocumentType.valueOf(d.documentType()),
                                        KycDocumentStatus.valueOf(d.status()),
                                        d.expiresOn()))
                .toList();
    }

    private DocumentRecord find(long customerId, UUID documentId) {
        return documents
                .find(customerId, documentId)
                .orElseThrow(
                        () ->
                                new DomainException(
                                        ErrorCode.KYC_DOCUMENT_NOT_FOUND, "Document introuvable."));
    }

    private KycDocumentView view(DocumentRecord d) {
        return new KycDocumentView(
                d.publicId(),
                d.documentType(),
                d.side(),
                d.status(),
                d.expiresOn(),
                d.uploadedAt(),
                d.rejectionReason(),
                storage.presignedGetUrl(d.storageKey(), LINK_TTL));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    private static KycDocumentType parseType(String value) {
        try {
            return KycDocumentType.valueOf(
                    value == null ? "" : value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("documentType", "Invalid", "Type de document inconnu.");
        }
    }

    private static DomainException invalid(String field, String code, String message) {
        return new DomainException(
                ErrorCode.VALIDATION_FAILED,
                message,
                List.of(new FieldViolation(field, code, message)));
    }
}
