package com.wtc.reference.application;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FeeScheduleView(
        long id,
        long pricingZoneId,
        String currency,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        BigDecimal percent,
        BigDecimal fixedFee,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {}
