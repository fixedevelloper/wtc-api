package com.wtc.customer.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * Règle de calcul du statut KYC d'un client à partir de ses documents :
 *
 * <ol>
 *   <li><b>VERIFIED</b> dès qu'une pièce d'identité approuvée est encore valable ;
 *   <li>sinon <b>PENDING</b> si un document attend un examen ;
 *   <li>sinon <b>REJECTED</b> si un document a été refusé ;
 *   <li>sinon <b>EXPIRED</b> s'il existe des documents mais plus aucun valable ;
 *   <li>sinon <b>NOT_SUBMITTED</b>.
 * </ol>
 *
 * Un document de complément (justificatif de domicile…) en attente ne fait donc pas retomber un
 * client déjà vérifié.
 */
public final class KycPolicy {

    private KycPolicy() {}

    /** Vue minimale d'un document pour le calcul. */
    public record Doc(KycDocumentType type, KycDocumentStatus status, LocalDate expiresOn) {

        boolean isValidApprovedIdentity(LocalDate today) {
            return type.isIdentity()
                    && status == KycDocumentStatus.APPROVED
                    && (expiresOn == null || !expiresOn.isBefore(today));
        }
    }

    public static KycStatus statusOf(List<Doc> documents, LocalDate today) {
        if (documents.stream().anyMatch(d -> d.isValidApprovedIdentity(today))) {
            return KycStatus.VERIFIED;
        }
        if (documents.stream().anyMatch(d -> d.status() == KycDocumentStatus.PENDING)) {
            return KycStatus.PENDING;
        }
        if (documents.stream().anyMatch(d -> d.status() == KycDocumentStatus.REJECTED)) {
            return KycStatus.REJECTED;
        }
        return documents.isEmpty() ? KycStatus.NOT_SUBMITTED : KycStatus.EXPIRED;
    }
}
