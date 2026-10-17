package com.wtc.customer.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Fiche client telle que l'expose le contrat (schéma {@code CustomerProfile}). Les valeurs absentes
 * sont nulles.
 */
public record CustomerProfile(
        UUID id,
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
        String kycStatus,
        String status,
        Instant createdAt) {}
