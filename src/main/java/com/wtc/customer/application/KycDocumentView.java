package com.wtc.customer.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record KycDocumentView(
        UUID id,
        String documentType,
        String side,
        String status,
        LocalDate expiresOn,
        Instant uploadedAt,
        String rejectionReason,
        String downloadUrl) {}
