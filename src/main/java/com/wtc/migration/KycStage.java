package com.wtc.migration;

import com.wtc.customer.domain.KycDocumentStatus;
import com.wtc.customer.domain.KycDocumentType;
import com.wtc.customer.domain.KycPolicy;
import com.wtc.customer.domain.KycStatus;
import com.wtc.shared.security.Tokens;
import com.wtc.shared.storage.FileTypes;
import com.wtc.shared.storage.ObjectStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pièces d'identité : un document par fichier présent, copié vers le stockage privé avec son
 * empreinte SHA-256. Le statut KYC est ensuite <b>recalculé par la règle du nouveau système</b>
 * ({@link KycPolicy}) et non recopié : sinon un client « vérifié » sans document retomberait en
 * attente dès qu'il en déposerait un. Un client vérifié sans pièce exploitable reçoit une pièce de
 * reprise approuvée (sans fichier) et une alerte dans le rapport.
 */
final class KycStage {

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private final EtlContext ctx;
    private final ObjectStorage storage;

    KycStage(EtlContext ctx, ObjectStorage storage) {
        this.ctx = ctx;
        this.storage = storage;
    }

    void run() {
        ctx.report.stage("kyc");
        for (Row c : ctx.rows("SELECT * FROM customer WHERE customeralt IS NULL ORDER BY id")) {
            EtlContext.Ref holder = ctx.holderByLegacyId.get(c.idOrZero());
            if (holder == null) {
                continue;
            }
            customer(c, holder);
        }
    }

    private void customer(Row c, EtlContext.Ref holder) {
        boolean verified = c.bool("isverify");
        LocalDate expires =
                c.instant("expireddatepiece") == null
                        ? null
                        : c.instant("expireddatepiece").atZone(ZoneOffset.UTC).toLocalDate();
        String typeText =
                c.str("typeidentification") != null
                        ? c.str("typeidentification")
                        : c.str("id_type");
        KycDocumentType type = typeOf(typeText);
        List<KycPolicy.Doc> docs = new ArrayList<>();
        String[][] files = {{"piecefacefile", "FRONT"}, {"piecerectofile", "BACK"}};
        for (String[] f : files) {
            if (c.str(f[0]) != null) {
                KycDocumentType docType = type;
                if (type == null) {
                    docType = KycDocumentType.NATIONAL_ID;
                    ctx.report.warn(
                            "customer",
                            c.idOrZero(),
                            "Type de pièce inconnu (« "
                                    + typeText
                                    + " ») : CNI retenue pour "
                                    + f[0],
                            c.forReport());
                }
                document(c, holder, docType, f[1], c.str(f[0]), verified, expires, docs);
            }
        }
        if (c.str("fileimagename") != null) {
            document(
                    c,
                    holder,
                    KycDocumentType.SELFIE,
                    "SINGLE",
                    c.str("fileimagename"),
                    verified,
                    null,
                    docs);
        }
        LocalDate today = ctx.today;
        KycStatus status = KycPolicy.statusOf(docs, today);
        boolean expired = expires != null && expires.isBefore(today);
        if (verified && !expired && status != KycStatus.VERIFIED) {
            // Vérifié dans l'ancien système mais aucune pièce d'identité exploitable (fichier
            // absent, ou entreprise avec RCCM seul).
            KycDocumentType docType =
                    type != null && type.isIdentity() ? type : KycDocumentType.NATIONAL_ID;
            placeholder(holder, docType, expires);
            docs.add(new KycPolicy.Doc(docType, KycDocumentStatus.APPROVED, expires));
            ctx.report.warn(
                    "customer",
                    c.idOrZero(),
                    "Client vérifié sans pièce exploitable : pièce de reprise approuvée créée (sans"
                            + " fichier)",
                    c.forReport());
            ctx.report.count("kyc.placeholder");
            status = KycPolicy.statusOf(docs, today);
        }
        if (verified && expired) {
            ctx.report.warn(
                    "customer",
                    c.idOrZero(),
                    "Pièce d'identité expirée le " + expires + " : le client n'est plus vérifié",
                    c.forReport());
            status = KycPolicy.statusOf(docs, today);
        }
        ctx.target
                .sql(
                        """
                        UPDATE customers SET kyc_status = :s,
                            kyc_verified_at = CASE WHEN :s = 'VERIFIED' THEN now() ELSE NULL END WHERE id = :id
                        """)
                .param("s", status.name())
                .param("id", holder.id())
                .update();
        ctx.report.count("kyc." + status.name().toLowerCase());
    }

    private void document(
            Row c,
            EtlContext.Ref holder,
            KycDocumentType type,
            String side,
            String relative,
            boolean verified,
            LocalDate expires,
            List<KycPolicy.Doc> docs) {
        Optional<byte[]> content = read(c, relative);
        if (content.isEmpty()) {
            return;
        }
        byte[] bytes = content.get();
        Optional<FileTypes.Kind> kind = FileTypes.detect(bytes);
        if (kind.isEmpty() || type.imageOnly() && !kind.get().isImage()) {
            ctx.report.warn(
                    "customer",
                    c.idOrZero(),
                    "Fichier « "
                            + relative
                            + " » : type non reconnu (ni JPEG, PNG ni PDF), non migré",
                    c.forReport());
            ctx.report.count("kyc.files_rejected");
            return;
        }
        boolean expired = expires != null && expires.isBefore(ctx.today);
        KycDocumentStatus status =
                !verified
                                || !type.isIdentity()
                                        && type != KycDocumentType.SELFIE
                                        && type != KycDocumentType.BUSINESS_REGISTRATION
                        ? KycDocumentStatus.PENDING
                        : expired && type.isIdentity()
                                ? KycDocumentStatus.EXPIRED
                                : KycDocumentStatus.APPROVED;
        UUID docId = UUID.randomUUID();
        String key = "kyc/" + holder.publicId() + "/" + docId;
        if (!ctx.props.dryRun()) {
            storage.put(key, bytes, kind.get().mediaType());
        }
        ctx.target
                .sql(
                        """
                        INSERT INTO kyc_documents (public_id, customer_id, document_type, side, storage_key, content_type, size_bytes, sha256,
                            status, expires_on, reviewed_by, reviewed_at)
                        VALUES (:pid, :customer, :type, :side, :key, :ct, :size, :sha, :status, :expires,
                            CASE WHEN :status IN ('APPROVED', 'EXPIRED') THEN :reviewer END,
                            CASE WHEN :status IN ('APPROVED', 'EXPIRED') THEN now() END)
                        """)
                .param("pid", docId)
                .param("customer", holder.id())
                .param("type", type.name())
                .param("side", side)
                .param("key", key)
                .param("ct", kind.get().mediaType())
                .param("size", bytes.length)
                .param("sha", Tokens.sha256Bytes(bytes))
                .param("status", status.name())
                .param(
                        "expires",
                        expires == null || !type.isIdentity() ? null : Date.valueOf(expires))
                .param("reviewer", ctx.migrationUserId)
                .update();
        docs.add(new KycPolicy.Doc(type, status, type.isIdentity() ? expires : null));
        ctx.report.count("kyc.documents");
    }

    private void placeholder(EtlContext.Ref holder, KycDocumentType type, LocalDate expires) {
        ctx.target
                .sql(
                        """
                        INSERT INTO kyc_documents (customer_id, document_type, side, storage_key, content_type, size_bytes, sha256, status,
                            expires_on, reviewed_by, reviewed_at)
                        VALUES (:customer, :type, 'SINGLE', :key, 'application/octet-stream', 1, :sha, 'APPROVED', :expires, :reviewer, now())
                        """)
                .param("customer", holder.id())
                .param("type", type.name())
                .param("key", "legacy/no-file/" + holder.publicId())
                .param("sha", "0".repeat(64))
                .param("expires", expires == null ? null : Date.valueOf(expires))
                .param("reviewer", ctx.migrationUserId)
                .update();
    }

    private KycDocumentType typeOf(String text) {
        String name = Parse.documentType(text);
        return name == null ? null : KycDocumentType.valueOf(name);
    }

    /** Lit un fichier de l'ancien dossier public/ ; refuse tout chemin qui en sortirait. */
    private Optional<byte[]> read(Row c, String relative) {
        Path root = ctx.props.publicDir();
        if (root == null) {
            ctx.report.warn(
                    "customer",
                    c.idOrZero(),
                    "Dossier public/ non fourni : fichier « " + relative + " » non migré",
                    c.forReport());
            ctx.report.count("kyc.files_missing");
            return Optional.empty();
        }
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root.normalize())) {
            ctx.report.error(
                    "customer",
                    c.idOrZero(),
                    "Chemin de fichier hors du dossier public/ : « " + relative + " » ignoré",
                    c.forReport());
            ctx.report.count("kyc.files_rejected");
            return Optional.empty();
        }
        try {
            if (!Files.isRegularFile(file)) {
                ctx.report.warn(
                        "customer",
                        c.idOrZero(),
                        "Fichier introuvable : « " + relative + " »",
                        c.forReport());
                ctx.report.count("kyc.files_missing");
                return Optional.empty();
            }
            if (Files.size(file) > MAX_FILE_BYTES) {
                ctx.report.warn(
                        "customer",
                        c.idOrZero(),
                        "Fichier trop volumineux (> 10 Mo) : « " + relative + " »",
                        c.forReport());
                ctx.report.count("kyc.files_rejected");
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            ctx.report.warn(
                    "customer",
                    c.idOrZero(),
                    "Lecture impossible de « " + relative + " » : " + e.getMessage(),
                    c.forReport());
            ctx.report.count("kyc.files_missing");
            return Optional.empty();
        }
    }
}
