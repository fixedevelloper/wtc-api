package com.wtc.reference.application;

/** Opérateur de mobile money tel que l'exposent le référentiel et les modules qui s'en servent. */
public record MobileOperatorView(
        long id,
        String countryCode,
        String name,
        String shortCode,
        boolean supportsPayout,
        boolean supportsPayin) {}
