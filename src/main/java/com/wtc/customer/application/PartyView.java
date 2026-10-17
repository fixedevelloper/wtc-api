package com.wtc.customer.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Expéditeur ou bénéficiaire. {@code customerId} et {@code customerName} ne sont renseignés que
 * pour l'administration.
 */
public record PartyView(
        UUID id,
        String role,
        PartyData data,
        String idDocumentNumberMasked,
        Instant createdAt,
        UUID customerId,
        String customerName) {}
