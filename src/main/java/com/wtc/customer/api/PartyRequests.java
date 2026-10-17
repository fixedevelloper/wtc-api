package com.wtc.customer.api;

import com.wtc.customer.application.PartyData;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Corps d'un expéditeur ou bénéficiaire ({@code PartyInput} du contrat). */
record PartyRequests() {

    record PartyInput(
            @NotNull @Pattern(regexp = "INDIVIDUAL|BUSINESS") String kind,
            @Size(max = 100) String firstName,
            @Size(max = 100) String lastName,
            @Size(max = 200) String businessName,
            @Size(max = 100) String businessType,
            LocalDate businessRegisteredOn,
            LocalDate birthDate,
            @Size(max = 100) String nationality,
            @Size(max = 100) String occupation,
            @Pattern(regexp = "M|F|OTHER") String gender,
            @Size(max = 20) String civility,
            @Size(max = 300) String addressLine,
            @Size(max = 100) String city,
            @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
            @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$") String phone,
            @Email @Size(max = 254) String email,
            @Size(max = 40) String idDocumentType,
            @Size(max = 64) String idDocumentNumber,
            LocalDate idDocumentExpiresOn,
            Boolean pep) {

        PartyData toData() {
            return new PartyData(
                    kind,
                    firstName,
                    lastName,
                    businessName,
                    businessType,
                    businessRegisteredOn,
                    birthDate,
                    nationality,
                    occupation,
                    gender,
                    civility,
                    addressLine,
                    city,
                    countryCode,
                    phone,
                    email,
                    idDocumentType,
                    idDocumentExpiresOn,
                    Boolean.TRUE.equals(pep));
        }
    }
}
