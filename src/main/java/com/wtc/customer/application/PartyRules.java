package com.wtc.customer.application;

import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Règles de cohérence d'une identité (client, expéditeur ou bénéficiaire). */
final class PartyRules {

    private static final LocalDate OLDEST_BIRTH = LocalDate.of(1900, 1, 1);

    private PartyRules() {}

    /**
     * @throws DomainException {@code VALIDATION_FAILED} avec le détail des champs en cause
     */
    static void requireConsistent(
            String kind,
            String firstName,
            String lastName,
            String businessName,
            LocalDate birthDate,
            LocalDate businessRegisteredOn,
            LocalDate today) {
        List<FieldViolation> violations = new ArrayList<>();
        if ("INDIVIDUAL".equals(kind)) {
            if (blank(firstName)) {
                violations.add(
                        new FieldViolation(
                                "firstName",
                                "Required",
                                "Le prénom est obligatoire pour un particulier."));
            }
            if (blank(lastName)) {
                violations.add(
                        new FieldViolation(
                                "lastName",
                                "Required",
                                "Le nom est obligatoire pour un particulier."));
            }
        } else if ("BUSINESS".equals(kind)) {
            if (blank(businessName)) {
                violations.add(
                        new FieldViolation(
                                "businessName",
                                "Required",
                                "La raison sociale est obligatoire pour une entreprise."));
            }
        } else {
            violations.add(new FieldViolation("kind", "Invalid", "INDIVIDUAL ou BUSINESS."));
        }
        if (birthDate != null && (birthDate.isAfter(today) || birthDate.isBefore(OLDEST_BIRTH))) {
            violations.add(
                    new FieldViolation(
                            "birthDate", "OutOfRange", "Date de naissance invraisemblable."));
        }
        if (businessRegisteredOn != null && businessRegisteredOn.isAfter(today)) {
            violations.add(
                    new FieldViolation(
                            "businessRegisteredOn",
                            "InFuture",
                            "La date d'immatriculation ne peut pas être future."));
        }
        if (!violations.isEmpty()) {
            throw new DomainException(
                    ErrorCode.VALIDATION_FAILED, "Certains champs sont invalides.", violations);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
