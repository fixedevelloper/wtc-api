package com.wtc.reference.infrastructure;

import com.wtc.reference.application.port.CountryAdminRepository;
import com.wtc.reference.domain.City;
import com.wtc.reference.domain.Country;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCountryAdminRepository implements CountryAdminRepository {

    private final JdbcClient jdbc;

    JdbcCountryAdminRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean existsByCodeOrIso3(String code, String iso3) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM countries WHERE code = :c OR iso3 = :i)")
                .param("c", code)
                .param("i", iso3)
                .query(Boolean.class)
                .single();
    }

    @Override
    public boolean zoneExists(long pricingZoneId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM pricing_zones WHERE id = :id)")
                .param("id", pricingZoneId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public Country insert(NewCountry c) {
        jdbc.sql(
                        """
                        INSERT INTO countries (code, iso3, name, currency_code, calling_code, flag_url,
                                               pricing_zone_id, can_send, can_receive)
                        VALUES (:code, :iso3, :name, :currency, :calling, :flag, :zone, :send, :receive)
                        """)
                .param("code", c.code())
                .param("iso3", c.iso3())
                .param("name", c.name())
                .param("currency", c.currencyCode())
                .param("calling", c.callingCode())
                .param("flag", c.flagUrl())
                .param("zone", c.pricingZoneId())
                .param("send", c.canSend())
                .param("receive", c.canReceive())
                .update();
        return new Country(
                c.code(),
                c.iso3(),
                c.name(),
                c.currencyCode(),
                c.callingCode(),
                c.flagUrl(),
                c.canSend(),
                c.canReceive());
    }

    @Override
    public boolean countryExists(String code) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM countries WHERE code = :c)")
                .param("c", code)
                .query(Boolean.class)
                .single();
    }

    @Override
    public boolean cityExists(String countryCode, String name) {
        return jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM cities WHERE country_code = :c"
                                + " AND upper(btrim(name)) = upper(btrim(:n)))")
                .param("c", countryCode)
                .param("n", name)
                .query(Boolean.class)
                .single();
    }

    @Override
    public City insertCity(String countryCode, String name) {
        long id =
                jdbc.sql(
                                "INSERT INTO cities (country_code, name) VALUES (:c, :n)"
                                        + " RETURNING id")
                        .param("c", countryCode)
                        .param("n", name)
                        .query(Long.class)
                        .single();
        return new City(id, name);
    }
}
