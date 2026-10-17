package com.wtc.reference.application.port;

import com.wtc.reference.domain.City;
import com.wtc.reference.domain.Country;
import java.util.List;
import java.util.Optional;

/** Port de lecture des pays et villes (implémenté par la couche infrastructure). */
public interface CountryRepository {

    List<Country> findAllEnabled();

    Optional<Country> findEnabledByCode(String code);

    List<City> findCitiesByCountryCode(String code);
}
