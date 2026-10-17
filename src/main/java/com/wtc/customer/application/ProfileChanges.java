package com.wtc.customer.application;

import java.time.LocalDate;

/** Modification partielle du profil : une valeur nulle signifie « inchangé ». */
public record ProfileChanges(
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
        String email) {}
