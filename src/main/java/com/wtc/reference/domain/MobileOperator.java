package com.wtc.reference.domain;

/** Opérateur de mobile money. */
public record MobileOperator(
        long id,
        String countryCode,
        String name,
        String shortCode,
        boolean supportsPayout,
        boolean supportsPayin) {}
