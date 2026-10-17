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
        Instant createdAt) {}
