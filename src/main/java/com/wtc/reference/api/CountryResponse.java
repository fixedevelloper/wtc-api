package com.wtc.reference.api;

import com.wtc.reference.domain.Country;

/** Représentation JSON d'un pays (schéma {@code Country} du contrat). */
public record CountryResponse(
        String code,
        String iso3,
        String name,
        String currencyCode,
        String callingCode,
        String flagUrl,
        boolean canSend,
        boolean canReceive) {

    static CountryResponse from(Country c) {
        return new CountryResponse(
                c.code(),
                c.iso3(),
                c.name(),
                c.currencyCode(),
                c.callingCode(),
                c.flagUrl(),
                c.canSend(),
                c.canReceive());
    }
}
