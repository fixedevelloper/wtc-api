package com.wtc.reference.infrastructure;

import com.wtc.reference.application.port.CountryRepository;
import com.wtc.reference.domain.City;
import com.wtc.reference.domain.Country;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Adaptateur JPA du port {@link CountryRepository}. */
@Repository
class JpaCountryRepository implements CountryRepository {

    private final SpringDataCountryRepository countries;
    private final SpringDataCityRepository cities;

    JpaCountryRepository(SpringDataCountryRepository countries, SpringDataCityRepository cities) {
        this.countries = countries;
        this.cities = cities;
    }

    @Override
    public List<Country> findAllEnabled() {
        return countries.findByEnabledTrueOrderByName().stream()
                .map(JpaCountryRepository::toDomain)
                .toList();
    }

    @Override
    public Optional<Country> findEnabledByCode(String code) {
        return countries.findByCodeAndEnabledTrue(code).map(JpaCountryRepository::toDomain);
    }

    @Override
    public List<City> findCitiesByCountryCode(String code) {
        return cities.findByCountryCodeOrderByName(code).stream()
                .map(c -> new City(c.id(), c.name()))
                .toList();
    }

    private static Country toDomain(CountryEntity e) {
        return new Country(
                e.code(),
                e.iso3(),
                e.name(),
                e.currencyCode(),
                e.callingCode(),
                e.flagUrl(),
                e.canSend(),
                e.canReceive());
    }
}
