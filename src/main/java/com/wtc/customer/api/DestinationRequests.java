package com.wtc.customer.api;

import com.wtc.customer.application.DestinationData;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Corps d'une destination de paiement ({@code DestinationInput} du contrat). */
record DestinationRequests() {

    record DestinationInput(
            @NotNull @Pattern(regexp = "BANK_ACCOUNT|MOBILE_WALLET") String kind,
            @NotNull @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
            @Positive Long bankId,
            @Positive Long mobileOperatorId,
            @Size(max = 200) String accountHolderName,
            @NotBlank @Size(max = 60) String accountNumber,
            @Size(max = 11) String swiftCode,
            @Size(max = 11) String ifscCode,
            @Size(max = 20) String routingNumber,
            Boolean isDefault) {

        DestinationData toData() {
            return new DestinationData(
                    kind,
                    countryCode,
                    bankId,
                    mobileOperatorId,
                    accountHolderName,
                    accountNumber,
                    swiftCode,
                    ifscCode,
                    routingNumber,
                    Boolean.TRUE.equals(isDefault));
        }
    }
}
