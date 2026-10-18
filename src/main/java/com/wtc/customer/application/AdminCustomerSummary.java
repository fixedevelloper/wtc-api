package com.wtc.customer.application;

import java.time.Instant;
import java.util.UUID;

public record AdminCustomerSummary(
        UUID id,
        String displayName,
        String kind,
        String email,
        String phone,
        String countryCode,
        String kycStatus,
        String status,
        Balance balance,
        Instant createdAt) {

    /**
     * Solde du portefeuille principal (XAF, à défaut le plus ancien) ; absent sans portefeuille.
     */
    public record Balance(String amount, String currency) {}
}
