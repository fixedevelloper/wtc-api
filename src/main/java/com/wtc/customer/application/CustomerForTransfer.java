package com.wtc.customer.application;

import java.util.UUID;

/** Ce que le module transfert doit savoir d'un client pour l'autoriser à envoyer. */
public record CustomerForTransfer(
        long internalId,
        UUID publicId,
        String displayName,
        String countryCode,
        String kycStatus,
        String status) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public boolean isVerified() {
        return "VERIFIED".equals(kycStatus);
    }
}
