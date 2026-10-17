package com.wtc.reference.application;

import java.math.BigDecimal;
import java.time.Instant;

public record ExchangeRateView(
        String baseCurrency,
        String quoteCurrency,
        BigDecimal rate,
        Instant effectiveFrom,
        String source) {}
