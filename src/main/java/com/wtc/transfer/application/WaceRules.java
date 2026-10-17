package com.wtc.transfer.application;

import com.wtc.customer.application.PartyData;
import com.wtc.customer.application.TransferParty;
import com.wtc.shared.error.FieldViolation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exigences de Wace sur les personnes d'un envoi (spécification v2.0.0, sections V et VI). Elles ne
 * bloquent pas la <b>saisie</b> d'un contact, mais bloquent la <b>création du transfert</b> : mieux
 * vaut refuser avant de débiter que échouer chez Wace après.
 */
final class WaceRules {

    /**
     * Types de pièce connus de Wace ; la spécification en cite d'autres sans les lister (question
     * ouverte).
     */
    static final Map<String, String> ID_TYPES =
            Map.of(
                    "PP",
                    "PP",
                    "PASSPORT",
                    "PP",
                    "CNI",
                    "CNI",
                    "NATIONAL_ID",
                    "CNI",
                    "ID_CARD",
                    "CNI",
                    "RCCM",
                    "RCCM");

    private WaceRules() {}

    static String idType(String ours) {
        return ours == null ? null : ID_TYPES.get(ours.strip().toUpperCase(Locale.ROOT));
    }

    /** Civilité attendue par Wace : {@code Maried}, {@code Single} ou {@code Others}. */
    static String civility(String ours) {
        if (ours == null || ours.isBlank()) {
            return null;
        }
        return switch (ours.strip().toUpperCase(Locale.ROOT)) {
            case "MARIED", "MARRIED", "MARIE", "MARIÉ", "MARIÉE", "MARIEE" -> "Maried";
            case "SINGLE", "CELIBATAIRE", "CÉLIBATAIRE" -> "Single";
            default -> "Others";
        };
    }

    static List<FieldViolation> check(
            TransferParty sender, TransferParty beneficiary, LocalDate today) {
        List<FieldViolation> v = new ArrayList<>();
        checkSender(sender, today, v);
        checkBeneficiary(beneficiary, v);
        return v;
    }

    private static void checkSender(TransferParty party, LocalDate today, List<FieldViolation> v) {
        PartyData d = party.data();
        String p = "sender.";
        boolean business = "BUSINESS".equals(d.kind());
        if (business) {
            require(v, p + "businessName", d.businessName());
            require(v, p + "businessType", d.businessType());
            if (d.businessRegisteredOn() == null) {
                v.add(
                        new FieldViolation(
                                p + "businessRegisteredOn",
                                "Required",
                                "Date d'immatriculation obligatoire pour une entreprise."));
            }
            if (!"RCCM".equals(idType(d.idDocumentType()))) {
                v.add(
                        new FieldViolation(
                                p + "idDocumentType",
                                "RccmRequired",
                                "Une entreprise s'identifie par son RCCM."));
            }
        } else {
            require(v, p + "firstName", d.firstName());
            require(v, p + "lastName", d.lastName());
            if (!"M".equals(d.gender()) && !"F".equals(d.gender())) {
                v.add(
                        new FieldViolation(
                                p + "gender", "Required", "Le fournisseur n'accepte que M ou F."));
            }
            if (civility(d.civility()) == null) {
                v.add(new FieldViolation(p + "civility", "Required", "Civilité obligatoire."));
            }
        }
        require(v, p + "addressLine", d.addressLine());
        require(v, p + "city", d.city());
        require(v, p + "countryCode", d.countryCode());
        require(v, p + "phone", d.phone());
        require(v, p + "nationality", d.nationality());
        require(v, p + "idDocumentNumber", party.idDocumentNumber());
        if (d.birthDate() == null) {
            v.add(
                    new FieldViolation(
                            p + "birthDate",
                            "Required",
                            "Date de naissance (du dirigeant pour une entreprise) obligatoire."));
        }
        if (d.idDocumentExpiresOn() == null) {
            v.add(
                    new FieldViolation(
                            p + "idDocumentExpiresOn",
                            "Required",
                            "Date d'expiration de la pièce obligatoire."));
        } else if (d.idDocumentExpiresOn().isBefore(today)) {
            v.add(
                    new FieldViolation(
                            p + "idDocumentExpiresOn",
                            "Expired",
                            "La pièce d'identité est expirée."));
        }
        checkIdType(v, p, d);
    }

    private static void checkBeneficiary(TransferParty party, List<FieldViolation> v) {
        PartyData d = party.data();
        String p = "beneficiary.";
        if ("BUSINESS".equals(d.kind())) {
            require(v, p + "businessName", d.businessName());
            require(v, p + "businessType", d.businessType());
            if (!"RCCM".equals(idType(d.idDocumentType()))) {
                v.add(
                        new FieldViolation(
                                p + "idDocumentType",
                                "RccmRequired",
                                "Une entreprise s'identifie par son RCCM."));
            }
        } else {
            require(v, p + "firstName", d.firstName());
            require(v, p + "lastName", d.lastName());
        }
        require(v, p + "addressLine", d.addressLine());
        require(v, p + "city", d.city());
        require(v, p + "countryCode", d.countryCode());
        require(v, p + "phone", d.phone());
        require(v, p + "idDocumentNumber", party.idDocumentNumber());
        checkIdType(v, p, d);
    }

    private static void checkIdType(List<FieldViolation> v, String prefix, PartyData d) {
        if (d.idDocumentType() == null || d.idDocumentType().isBlank()) {
            v.add(
                    new FieldViolation(
                            prefix + "idDocumentType", "Required", "Type de pièce obligatoire."));
        } else if (idType(d.idDocumentType()) == null) {
            v.add(
                    new FieldViolation(
                            prefix + "idDocumentType",
                            "UnsupportedType",
                            "Type de pièce non reconnu par le fournisseur (PASSPORT, NATIONAL_ID,"
                                    + " RCCM)."));
        }
    }

    private static void require(List<FieldViolation> v, String field, String value) {
        if (value == null || value.isBlank()) {
            v.add(new FieldViolation(field, "Required", "Champ obligatoire pour l'envoi."));
        }
    }
}
