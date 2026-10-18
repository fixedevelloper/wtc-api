package com.wtc.reference;

import static com.wtc.support.Api.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.identity.IdentityFixtures;
import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.identity.domain.Role;
import com.wtc.support.AbstractIT;
import com.wtc.support.Api;
import com.wtc.support.Api.Reply;
import com.wtc.support.Sessions;
import com.wtc.support.TestClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Ajout d'un pays depuis le back-office. */
class CountryAdminIT extends AbstractIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;
    @Autowired TestClock clock;

    Api api;
    IdentityFixtures fx;

    @BeforeAll
    static void seed(
            @Autowired JdbcClient jdbc,
            @Autowired UserRepository users,
            @Autowired PasswordHasher hasher) {
        new IdentityFixtures(jdbc, users, hasher).seedCatalog();
    }

    @AfterAll
    static void cleanAll(
            @Autowired JdbcClient jdbc,
            @Autowired UserRepository users,
            @Autowired PasswordHasher hasher) {
        new IdentityFixtures(jdbc, users, hasher).cleanUp();
    }

    @BeforeEach
    void init() {
        api = new Api(mvc);
        fx = new IdentityFixtures(jdbc, users, hasher);
        clock.reset();
        removeTestCountry();
    }

    @AfterEach
    void clean() {
        removeTestCountry();
        fx.cleanUp();
    }

    private void removeTestCountry() {
        jdbc.sql("DELETE FROM cities WHERE country_code = 'KK'").update();
        jdbc.sql("DELETE FROM countries WHERE code = 'KK'").update();
    }

    private static Object body() {
        return map(
                "code", "KK",
                "iso3", "KKK",
                "name", "Kakoland",
                "currencyCode", "XOF",
                "callingCode", "+999",
                "canReceive", true);
    }

    @Test
    void anAdminAddsACountryThatThePublicListThenShows() {
        String t = Sessions.staff(api, fx, clock, Role.ADMIN).accessToken();

        Reply reply = api.post("/api/admin/countries", body(), t);

        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.contractViolations("adminCreateCountry", 201)).isEmpty();
        assertThat(reply.json().get("code").asString()).isEqualTo("KK");
        assertThat(reply.json().get("canReceive").asBoolean()).isTrue();
        assertThat(reply.json().get("canSend").asBoolean()).isFalse();
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM audit_log WHERE action ="
                                                + " 'COUNTRY_CREATED' AND entity_id = 'KK'")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        assertThat(api.get("/api/countries", null).json().toString()).contains("Kakoland");
    }

    @Test
    void aCountryCodeOrIso3AlreadyUsedIsRefused() {
        String t = Sessions.staff(api, fx, clock, Role.ADMIN).accessToken();
        assertThat(api.post("/api/admin/countries", body(), t).status()).isEqualTo(201);

        Reply again = api.post("/api/admin/countries", body(), t);

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.contractViolations("adminCreateCountry", 409)).isEmpty();
    }

    @Test
    void invalidInputAndUnknownZoneAreRefused() {
        String t = Sessions.staff(api, fx, clock, Role.ADMIN).accessToken();

        Reply badCode =
                api.post(
                        "/api/admin/countries",
                        map("code", "K", "iso3", "KKK", "name", "x", "currencyCode", "XOF"),
                        t);
        Reply badZone =
                api.post(
                        "/api/admin/countries",
                        map(
                                "code", "KK",
                                "iso3", "KKK",
                                "name", "x",
                                "currencyCode", "XOF",
                                "pricingZoneId", 999999999),
                        t);

        assertThat(badCode.status()).isEqualTo(422);
        assertThat(badCode.contractViolations("adminCreateCountry", 422)).isEmpty();
        assertThat(badZone.status()).isEqualTo(422);
        assertThat(
                        jdbc.sql("SELECT count(*) FROM countries WHERE code = 'KK'")
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void onlyReferenceWritersMayAddACountry() {
        String support = Sessions.staff(api, fx, clock, Role.SUPPORT).accessToken();
        String customer = Sessions.customer(api, fx, "client").accessToken();

        assertThat(api.post("/api/admin/countries", body(), support).status()).isEqualTo(403);
        assertThat(api.post("/api/admin/countries", body(), customer).status()).isEqualTo(403);
        assertThat(api.post("/api/admin/countries", body(), null).status()).isEqualTo(401);
    }

    @Test
    void anAdminAddsCitiesToACountryWithoutDuplicates() {
        String t = Sessions.staff(api, fx, clock, Role.ADMIN).accessToken();
        api.post("/api/admin/countries", body(), t);

        Reply created = api.post("/api/admin/countries/KK/cities", map("name", " Kakoville "), t);
        Reply twice = api.post("/api/admin/countries/KK/cities", map("name", "kakoville"), t);
        Reply unknown = api.post("/api/admin/countries/QQ/cities", map("name", "X"), t);
        Reply blank = api.post("/api/admin/countries/KK/cities", map("name", " "), t);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.contractViolations("adminAddCity", 201)).isEmpty();
        assertThat(created.json().get("name").asString()).isEqualTo("Kakoville");
        assertThat(twice.status()).isEqualTo(409);
        assertThat(twice.contractViolations("adminAddCity", 409)).isEmpty();
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(blank.status()).isEqualTo(422);
        assertThat(api.get("/api/countries/KK/cities", null).json().toString())
                .contains("Kakoville");
        assertThat(
                        api.post(
                                        "/api/admin/countries/KK/cities",
                                        map("name", "Z"),
                                        Sessions.staff(api, fx, clock, Role.SUPPORT).accessToken())
                                .status())
                .isEqualTo(403);
    }
}
