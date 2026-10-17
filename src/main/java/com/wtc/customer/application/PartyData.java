package com.wtc.customer.application;

import java.time.LocalDate;

/**
 * Champs modifiables d'un expéditeur ou bénéficiaire (hors numéro de pièce, qui est chiffré à
 * part).
 */
public record PartyData(
        String kind,
        String firstName,
        String lastName,
        String businessName,
        String businessType,
        LocalDate businessRegisteredOn,
        LocalDate birthDate,
        String nationality,
        String occupation,
        String gender,
        String civility,
        String addressLine,
        String city,
        String countryCode,
        String phone,
        String email,
        String idDocumentType,
        LocalDate idDocumentExpiresOn,
        boolean pep) {}
