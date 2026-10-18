package com.wtc.reference.application.port;

import com.wtc.reference.domain.City;
import com.wtc.reference.domain.Country;

/** Port d'écriture des pays (administration). */
public interface CountryAdminRepository {

    record NewCountry(
            String code,
            String iso3,
            String name,
            String currencyCode,
            String callingCode,
            String flagUrl,
            Long pricingZoneId,
            boolean canSend,
            boolean canReceive) {}

    boolean existsByCodeOrIso3(String code, String iso3);

    boolean zoneExists(long pricingZoneId);

    Country insert(NewCountry country);

    boolean countryExists(String code);

    boolean cityExists(String countryCode, String name);

    City insertCity(String countryCode, String name);
}
