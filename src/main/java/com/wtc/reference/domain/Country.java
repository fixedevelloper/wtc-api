package com.wtc.reference.domain;

import java.util.Objects;

/** Pays pris en charge. {@code code} est l'ISO 3166-1 alpha-2, {@code currencyCode} l'ISO 4217. */
public record Country(
        String code,
        String iso3,
        String name,
        String currencyCode,
        String callingCode,
        String flagUrl,
        boolean canSend,
        boolean canReceive) {

    public Country {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(currencyCode, "currencyCode");
    }
}
