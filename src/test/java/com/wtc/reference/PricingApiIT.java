package com.wtc.reference;

import static com.wtc.support.Api.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wtc.identity.IdentityFixtures;
import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.identity.domain.Role;
import com.wtc.reference.application.PricingQuote;
import com.wtc.reference.application.PricingService;
import com.wtc.shared.error.DomainException;
import com.wtc.support.AbstractIT;
import com.wtc.support.Api;
import com.wtc.support.Api.Reply;
import com.wtc.support.Sessions;
import com.wtc.support.Sessions.Session;
import com.wtc.support.TestClock;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Tarification et référentiels : administration par HTTP et moteur de devis sur PostgreSQL. */
class PricingApiIT extends AbstractIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;
    @Autowired TestClock clock;
    @Autowired PricingService pricing;

    Api api;
    IdentityFixtures fx;
    long zoneYy;
    long zoneVv;

    @BeforeAll
    static void seed(
            @Autowired JdbcClient jdbc,
            @Autowired UserRepository users,
            @Autowired PasswordHasher hasher) {
        new IdentityFixtures(jdbc, users, hasher).seedCatalog();
        jdbc.sql(
                        "INSERT INTO countries (code, iso3, name, currency_code, can_send,"
                            + " can_receive) VALUES ('VV', 'VVV', 'Pays en euros', 'EUR', false,"
                            + " true) ON CONFLICT DO NOTHING")
                .update();
    }

    @AfterAll
    static void clean(
            @Autowired JdbcClient jdbc,
            @Autowired UserRepository users,
            @Autowired PasswordHasher hasher) {
        wipePricing(jdbc);
        jdbc.sql("DELETE FROM reference_values").update();
        jdbc.sql("DELETE FROM countries WHERE code = 'VV'").update();
        new IdentityFixtures(jdbc, users, hasher).cleanUp();
    }

    private static void wipePricing(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM fee_schedules").update();
        jdbc.sql("DELETE FROM exchange_rates").update();
        jdbc.sql("UPDATE countries SET pricing_zone_id = NULL").update();
        jdbc.sql("DELETE FROM pricing_zones").update();
    }

    @BeforeEach
    void init() {
        api = new Api(mvc);
        fx = new IdentityFixtures(jdbc, users, hasher);
        clock.reset();
        wipePricing(jdbc);
        zoneYy =
                jdbc.sql(
                                "INSERT INTO pricing_zones (code, name) VALUES ('ZONE-YY', 'Zone"
                                        + " YY') RETURNING id")
                        .query(Long.class)
                        .single();
        zoneVv =
                jdbc.sql(
                                "INSERT INTO pricing_zones (code, name) VALUES ('ZONE-VV', 'Zone"
                                        + " VV') RETURNING id")
                        .query(Long.class)
                        .single();
        jdbc.sql("UPDATE countries SET pricing_zone_id = :z WHERE code = 'YY'")
                .param("z", zoneYy)
                .update();
        jdbc.sql("UPDATE countries SET pricing_zone_id = :z WHERE code = 'VV'")
                .param("z", zoneVv)
                .update();
    }

    private String admin() {
        return Sessions.staff(api, fx, clock, Role.ADMIN).accessToken();
    }

    private void band(
            long zone,
            String currency,
            String min,
            String max,
            String percent,
            String fixed,
            LocalDate from) {
        jdbc.sql(
                        "INSERT INTO fee_schedules (pricing_zone_id, currency_code, min_amount,"
                                + " max_amount, percent, fixed_fee, effective_from) VALUES (:z, :c,"
                                + " :min, :max, :p, :f, :from)")
                .param("z", zone)
                .param("c", currency)
                .param("min", new BigDecimal(min))
                .param("max", new BigDecimal(max))
                .param("p", new BigDecimal(percent))
                .param("f", new BigDecimal(fixed))
                .param("from", java.sql.Date.valueOf(from))
                .update();
    }

    private void rate(String base, String quote, String rate) {
        jdbc.sql(
                        "INSERT INTO exchange_rates (base_currency, quote_currency, rate,"
                                + " effective_from) VALUES (:b, :q, :r, now() - interval '1 day')")
                .param("b", base)
                .param("q", quote)
                .param("r", new BigDecimal(rate))
                .update();
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------ moteur de devis

    @Test
    void quotesFeeTotalAndReceivedAmountFromTheDestinationZone() {
        band(zoneYy, "XAF", "0", "100000", "5", "0", today().minusYears(1));
        band(zoneYy, "XAF", "100000", "1000000", "3.5", "500", today().minusYears(1));
        rate("XAF", "XOF", "1");

        PricingQuote small = pricing.quote("XAF", "YY", new BigDecimal("10000"));
        assertThat(small.fee()).isEqualByComparingTo("500");
        assertThat(small.totalDebit()).isEqualByComparingTo("10500");
        assertThat(small.destinationAmount()).isEqualByComparingTo("10000");
        assertThat(small.destinationCurrency()).isEqualTo("XOF");
        assertThat(small.feePercent()).isEqualByComparingTo("5");

        PricingQuote big = pricing.quote("XAF", "YY", new BigDecimal("200000"));
        assertThat(big.fee()).as("3,5 % de 200 000 + 500 fixes").isEqualByComparingTo("7500");
        assertThat(pricing.quote("XAF", "YY", new BigDecimal("100000")).feePercent())
                .as("100 000 appartient à la tranche basse")
                .isEqualByComparingTo("5");
    }

    @Test
    void usesTheInverseRateAndRoundsTheReceivedAmountDown() {
        band(zoneVv, "XAF", "0", "10000000", "4", "0", today().minusDays(1));
        rate("EUR", "XAF", "655.957");

        PricingQuote q = pricing.quote("XAF", "VV", new BigDecimal("100000"));

        assertThat(q.destinationCurrency()).isEqualTo("EUR");
        assertThat(q.destinationAmount())
                .as("100000 / 655,957 = 152,4490… arrondi vers le bas")
                .isEqualByComparingTo("152.44");
        assertThat(q.fxRate())
                .isBetween(new BigDecimal("0.00152449"), new BigDecimal("0.00152450"));
        assertThat(q.fee()).isEqualByComparingTo("4000");
    }

    @Test
    void theMostRecentRateWinsAndFutureRatesAreIgnored() {
        band(zoneYy, "XAF", "0", "1000000", "1", "0", today().minusDays(1));
        rate("XAF", "XOF", "1");
        jdbc.sql(
                        "INSERT INTO exchange_rates (base_currency, quote_currency, rate,"
                                + " effective_from) VALUES ('XAF', 'XOF', 2, now() - interval '1"
                                + " hour')")
                .update();
        jdbc.sql(
                        "INSERT INTO exchange_rates (base_currency, quote_currency, rate,"
                            + " effective_from) VALUES ('XAF', 'XOF', 9, now() + interval '1 day')")
                .update();

        assertThat(pricing.quote("XAF", "YY", new BigDecimal("1000")).destinationAmount())
                .isEqualByComparingTo("2000");
    }

    @Test
    void refusesWhatItCannotPriceInsteadOfGuessing() {
        band(zoneYy, "XAF", "0", "100000", "5", "0", today().minusDays(1));
        rate("XAF", "XOF", "1");

        assertCode(
                () -> pricing.quote("XAF", "YY", new BigDecimal("100001")), "FEE_NOT_CONFIGURED");
        assertCode(() -> pricing.quote("EUR", "YY", new BigDecimal("10")), "FEE_NOT_CONFIGURED");
        assertCode(
                () -> pricing.quote("XAF", "WW", new BigDecimal("10")),
                "DESTINATION_NOT_SUPPORTED");
        assertCode(
                () -> pricing.quote("XAF", "QQ", new BigDecimal("10")),
                "DESTINATION_NOT_SUPPORTED");
        assertCode(
                () -> pricing.quote("XAF", "ZZ", new BigDecimal("10")),
                "FEE_NOT_CONFIGURED"); // ZZ n'a pas de zone
        assertCode(() -> pricing.quote("XAF", "YY", BigDecimal.ZERO), "VALIDATION_FAILED");
        assertCode(() -> pricing.quote("XAF", "YY", new BigDecimal("-5")), "VALIDATION_FAILED");
        assertCode(() -> pricing.quote("XAF", "YY", new BigDecimal("10.5")), "VALIDATION_FAILED");
        assertCode(() -> pricing.quote("ZZZ", "YY", new BigDecimal("10")), "VALIDATION_FAILED");

        jdbc.sql("DELETE FROM exchange_rates").update();
        assertCode(
                () -> pricing.quote("XAF", "YY", new BigDecimal("1000")),
                "EXCHANGE_RATE_UNAVAILABLE");
    }

    @Test
    void bandsNotYetInForceOrAlreadyEndedDoNotApply() {
        rate("XAF", "XOF", "1");
        band(zoneYy, "XAF", "0", "100000", "5", "0", today().plusDays(1));
        assertCode(() -> pricing.quote("XAF", "YY", new BigDecimal("1000")), "FEE_NOT_CONFIGURED");
        jdbc.sql("UPDATE fee_schedules SET effective_from = :f, effective_to = :t")
                .param("f", java.sql.Date.valueOf(today().minusDays(10)))
                .param("t", java.sql.Date.valueOf(today()))
                .update();
        assertCode(() -> pricing.quote("XAF", "YY", new BigDecimal("1000")), "FEE_NOT_CONFIGURED");
    }

    @Test
    void aTinyAmountThatWouldDeliverNothingIsRefused() {
        band(zoneVv, "XAF", "0", "100000", "0", "0", today().minusDays(1));
        rate("EUR", "XAF", "655.957");
        assertCode(() -> pricing.quote("XAF", "VV", new BigDecimal("1")), "VALIDATION_FAILED");
    }

    private static void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        DomainException.class, e -> assertThat(e.code().name()).isEqualTo(code));
    }

    // ------------------------------------------------------------------ administration

    @Test
    void zonesAreCreatedWithTheirCountriesAndCodesStayUnique() {
        String t = admin();
        Reply created =
                api.post(
                        "/api/admin/pricing-zones",
                        map(
                                "code",
                                "ZONE-NEW",
                                "name",
                                "Nouvelle zone",
                                "countries",
                                List.of("WW")),
                        t);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.contractViolations("adminCreatePricingZone", 201)).isEmpty();
        assertThat(created.json().get("countries").get(0).asString()).isEqualTo("WW");

        assertThat(
                        api.post(
                                        "/api/admin/pricing-zones",
                                        map("code", "zone-new", "name", "Doublon"),
                                        t)
                                .code())
                .isEqualTo("PRICING_ZONE_EXISTS");
        assertThat(
                        api.post(
                                        "/api/admin/pricing-zones",
                                        map(
                                                "code",
                                                "ZONE-X",
                                                "name",
                                                "X",
                                                "countries",
                                                List.of("QQ")),
                                        t)
                                .code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(
                        jdbc.sql("SELECT count(*) FROM pricing_zones WHERE code = 'ZONE-X'")
                                .query(Long.class)
                                .single())
                .as("rien d'enregistré en cas d'erreur")
                .isZero();

        Reply list = api.get("/api/admin/pricing-zones", t);
        assertThat(list.contractViolations("adminListPricingZones", 200)).isEmpty();
        assertThat(list.json().size()).isEqualTo(3);
    }

    @Test
    void feeSchedulesRejectOverlapsPastDatesAndNonsense() {
        String t = admin();
        String start = today().plusDays(1).toString();
        Api.Reply first =
                api.post(
                        "/api/admin/fee-schedules",
                        map(
                                "pricingZoneId",
                                zoneYy,
                                "currency",
                                "XAF",
                                "minAmount",
                                "0",
                                "maxAmount",
                                "100000",
                                "percent",
                                5,
                                "effectiveFrom",
                                start),
                        t);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.contractViolations("adminCreateFeeSchedule", 201)).isEmpty();

        Reply overlap =
                api.post(
                        "/api/admin/fee-schedules",
                        map(
                                "pricingZoneId",
                                zoneYy,
                                "currency",
                                "XAF",
                                "minAmount",
                                "50000",
                                "maxAmount",
                                "200000",
                                "percent",
                                4,
                                "effectiveFrom",
                                start),
                        t);
        assertThat(overlap.status()).isEqualTo(409);
        assertThat(overlap.code()).isEqualTo("FEE_SCHEDULE_OVERLAP");
        assertThat(
                        api.post(
                                        "/api/admin/fee-schedules",
                                        map(
                                                "pricingZoneId",
                                                zoneYy,
                                                "currency",
                                                "XAF",
                                                "minAmount",
                                                "100000",
                                                "maxAmount",
                                                "200000",
                                                "percent",
                                                4,
                                                "effectiveFrom",
                                                start),
                                        t)
                                .status())
                .as("tranche adjacente : acceptée")
                .isEqualTo(201);
        assertThat(
                        api.post(
                                        "/api/admin/fee-schedules",
                                        map(
                                                "pricingZoneId",
                                                zoneYy,
                                                "currency",
                                                "EUR",
                                                "minAmount",
                                                "0",
                                                "maxAmount",
                                                "100000",
                                                "percent",
                                                5,
                                                "effectiveFrom",
                                                start),
                                        t)
                                .status())
                .as("autre devise : pas de conflit")
                .isEqualTo(201);

        assertThat(
                        api.post(
                                        "/api/admin/fee-schedules",
                                        map(
                                                "pricingZoneId",
                                                zoneYy,
                                                "currency",
                                                "XAF",
                                                "minAmount",
                                                "0",
                                                "maxAmount",
                                                "10",
                                                "percent",
                                                5,
                                                "effectiveFrom",
                                                today().minusDays(1).toString()),
                                        t)
                                .code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(
                        api.post(
                                        "/api/admin/fee-schedules",
                                        map(
                                                "pricingZoneId",
                                                zoneYy,
                                                "currency",
                                                "XAF",
                                                "minAmount",
                                                "10",
                                                "maxAmount",
                                                "10",
                                                "percent",
                                                5,
                                                "effectiveFrom",
                                                start),
                                        t)
                                .code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(
                        api.post(
                                        "/api/admin/fee-schedules",
                                        map(
                                                "pricingZoneId",
                                                999999,
                                                "currency",
                                                "XAF",
                                                "minAmount",
                                                "0",
                                                "maxAmount",
                                                "10",
                                                "percent",
                                                5,
                                                "effectiveFrom",
                                                start),
                                        t)
                                .code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(
                        api.post(
                                        "/api/admin/fee-schedules",
                                        map(
                                                "pricingZoneId",
                                                zoneYy,
                                                "currency",
                                                "XAF",
                                                "minAmount",
                                                "0",
                                                "maxAmount",
                                                "10",
                                                "percent",
                                                101,
                                                "effectiveFrom",
                                                start),
                                        t)
                                .status())
                .isIn(400, 422);
        assertThat(
                        api.post(
                                        "/api/admin/fee-schedules",
                                        map(
                                                "pricingZoneId",
                                                zoneYy,
                                                "currency",
                                                "ZZZ",
                                                "minAmount",
                                                "0",
                                                "maxAmount",
                                                "10",
                                                "percent",
                                                5,
                                                "effectiveFrom",
                                                start),
                                        t)
                                .code())
                .isEqualTo("VALIDATION_FAILED");

        Reply list = api.get("/api/admin/fee-schedules", t);
        assertThat(list.contractViolations("adminListFeeSchedules", 200)).isEmpty();
        assertThat(list.json().size()).isEqualTo(3);
    }

    @Test
    void aFeeScheduleCanBeEndedButNeverDeletedOrBackdated() {
        String t = admin();
        band(zoneYy, "XAF", "0", "100000", "5", "0", today().minusDays(30));
        long id = jdbc.sql("SELECT id FROM fee_schedules").query(Long.class).single();
        String url = "/api/admin/fee-schedules/" + id;

        assertThat(api.patch(url, map("effectiveTo", today().minusDays(1).toString()), t).code())
                .as("pas dans le passé")
                .isEqualTo("VALIDATION_FAILED");
        assertThat(api.patch(url, map("effectiveTo", today().minusDays(31).toString()), t).code())
                .isEqualTo("VALIDATION_FAILED");
        Reply ended = api.patch(url, map("effectiveTo", today().plusDays(10).toString()), t);
        assertThat(ended.status()).isEqualTo(200);
        assertThat(ended.contractViolations("adminEndFeeSchedule", 200)).isEmpty();
        assertThat(ended.text("effectiveTo")).isEqualTo(today().plusDays(10).toString());
        assertThat(api.patch(url, map("effectiveTo", today().plusDays(20).toString()), t).code())
                .as("on raccourcit, on ne prolonge pas")
                .isEqualTo("VALIDATION_FAILED");
        assertThat(
                        api.patch(
                                        "/api/admin/fee-schedules/987654",
                                        map("effectiveTo", today().plusDays(5).toString()),
                                        t)
                                .code())
                .isEqualTo("FEE_SCHEDULE_NOT_FOUND");
    }

    @Test
    void exchangeRatesAreAppendedNeverEdited() {
        String t = admin();
        Reply created =
                api.post(
                        "/api/admin/exchange-rates",
                        map(
                                "baseCurrency",
                                "EUR",
                                "quoteCurrency",
                                "XAF",
                                "rate",
                                655.957,
                                "effectiveFrom",
                                "2030-01-01T00:00:00Z"),
                        t);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.contractViolations("adminCreateExchangeRate", 201)).isEmpty();
        assertThat(created.json().get("rate").decimalValue()).isEqualByComparingTo("655.957");
        assertThat(
                        api.post(
                                        "/api/admin/exchange-rates",
                                        map(
                                                "baseCurrency",
                                                "EUR",
                                                "quoteCurrency",
                                                "XAF",
                                                "rate",
                                                656,
                                                "effectiveFrom",
                                                "2030-01-01T00:00:00Z"),
                                        t)
                                .code())
                .isEqualTo("EXCHANGE_RATE_EXISTS");
        assertThat(
                        api.post(
                                        "/api/admin/exchange-rates",
                                        map(
                                                "baseCurrency",
                                                "EUR",
                                                "quoteCurrency",
                                                "EUR",
                                                "rate",
                                                1),
                                        t)
                                .code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(
                        api.post(
                                        "/api/admin/exchange-rates",
                                        map(
                                                "baseCurrency",
                                                "EUR",
                                                "quoteCurrency",
                                                "XAF",
                                                "rate",
                                                0),
                                        t)
                                .status())
                .isIn(400, 422);
        assertThat(
                        api.post(
                                        "/api/admin/exchange-rates",
                                        map(
                                                "baseCurrency",
                                                "EUR",
                                                "quoteCurrency",
                                                "XAF",
                                                "rate",
                                                1.00000000001),
                                        t)
                                .code())
                .as("trop de décimales")
                .isEqualTo("VALIDATION_FAILED");
        assertThat(
                        api.post(
                                        "/api/admin/exchange-rates",
                                        map(
                                                "baseCurrency",
                                                "EUR",
                                                "quoteCurrency",
                                                "USD",
                                                "rate",
                                                1.08),
                                        t)
                                .status())
                .as("date par défaut : maintenant")
                .isEqualTo(201);

        Reply page = api.get("/api/admin/exchange-rates?size=1&sort=effectiveFrom,desc", t);
        assertThat(page.contractViolations("adminListExchangeRates", 200)).isEmpty();
        assertThat(page.json().get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.json().get("content").get(0).get("baseCurrency").asString())
                .isEqualTo("EUR");
    }

    @Test
    void settingsAreTypedAndOnlyExistingOnesCanChange() {
        String t = admin();
        Reply list = api.get("/api/admin/settings", t);
        assertThat(list.contractViolations("adminListSettings", 200)).isEmpty();
        assertThat(list.json().size()).isGreaterThanOrEqualTo(3);

        String original =
                jdbc.sql(
                                "SELECT value::text FROM platform_settings WHERE key ="
                                        + " 'transfer.max_amount_per_transfer'")
                        .query(String.class)
                        .single();
        try {
            Reply ok =
                    api.put(
                            "/api/admin/settings/transfer.max_amount_per_transfer",
                            map("value", 2500000),
                            t);
            assertThat(ok.status()).isEqualTo(200);
            assertThat(ok.contractViolations("adminUpdateSetting", 200)).isEmpty();
            assertThat(ok.json().get("value").decimalValue()).isEqualByComparingTo("2500000");
            assertThat(
                            jdbc.sql(
                                            "SELECT before_state::text || after_state::text FROM"
                                                    + " audit_log WHERE action = 'SETTING_UPDATED'"
                                                    + " ORDER BY id DESC LIMIT 1")
                                    .query(String.class)
                                    .single())
                    .as("avant et après sont journalisés")
                    .contains(original.trim())
                    .contains("2500000");
        } finally {
            jdbc.sql(
                            "UPDATE platform_settings SET value = CAST(:v AS jsonb) WHERE key ="
                                    + " 'transfer.max_amount_per_transfer'")
                    .param("v", original)
                    .update();
        }
        assertThat(
                        api.put(
                                        "/api/admin/settings/transfer.max_amount_per_transfer",
                                        map("value", "beaucoup"),
                                        t)
                                .code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(
                        api.put(
                                        "/api/admin/settings/transfer.max_amount_per_transfer",
                                        map("value", -5),
                                        t)
                                .code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(
                        api.put(
                                        "/api/admin/settings/transfer.enforce_fee_schedule",
                                        map("value", "oui"),
                                        t)
                                .code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(
                        api.put(
                                        "/api/admin/settings/wallet.default_currency",
                                        map("value", "xaf"),
                                        t)
                                .code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(
                        api.put(
                                        "/api/admin/settings/client.requirements",
                                        map(
                                                "value",
                                                List.of(
                                                        map(
                                                                "platform",
                                                                "IOS",
                                                                "minimumVersion",
                                                                "1.0.0"))),
                                        t)
                                .code())
                .isEqualTo("VALIDATION_FAILED");
        Reply unknown = api.put("/api/admin/settings/pirate.key", map("value", 1), t);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.code()).isEqualTo("SETTING_NOT_FOUND");
    }

    @Test
    void onlyStaffWithReferenceWriteTouchPricing() {
        Session support = Sessions.staff(api, fx, clock, Role.SUPPORT);
        Session customer = Sessions.customer(api, fx, "client");
        for (String path :
                List.of(
                        "/api/admin/pricing-zones",
                        "/api/admin/fee-schedules",
                        "/api/admin/exchange-rates",
                        "/api/admin/settings")) {
            assertThat(api.get(path, support.accessToken()).status())
                    .as(path + " (support)")
                    .isEqualTo(403);
            assertThat(api.get(path, customer.accessToken()).status())
                    .as(path + " (client)")
                    .isEqualTo(403);
            assertThat(api.get(path, null).status()).isEqualTo(401);
        }
        assertThat(
                        api.post(
                                        "/api/admin/exchange-rates",
                                        map(
                                                "baseCurrency",
                                                "EUR",
                                                "quoteCurrency",
                                                "XAF",
                                                "rate",
                                                655),
                                        support.accessToken())
                                .status())
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------ listes de référence

    @Test
    void referenceListsAreFilteredByTransferType() {
        jdbc.sql(
                        """
                        INSERT INTO reference_values (category, party_type_pair, code, label, wace_code, enabled) VALUES
                            ('TRANSFER_PURPOSE', 'P2P', 'FAMILY', 'Soutien familial', 'Family Maintainance', true),
                            ('TRANSFER_PURPOSE', 'B2B', 'SOFTWARE', 'Export de logiciels', 'Software Export', true),
                            ('TRANSFER_PURPOSE', 'ALL', 'GIFT', 'Cadeau', 'Gift', true),
                            ('TRANSFER_PURPOSE', 'P2P', 'OLD', 'Ancien motif', 'Old', false),
                            ('SOURCE_OF_FUNDS', 'ALL', 'SALARY', 'Salaire', 'Salary', true),
                            ('RELATIONSHIP', 'ALL', 'BROTHER', 'Frère', 'Brother', true)
                        """)
                .update();
        String t = Sessions.customer(api, fx, "client").accessToken();

        Reply p2p = api.get("/api/reference/transfer-purposes?partyTypePair=P2P", t);
        assertThat(p2p.status()).isEqualTo(200);
        assertThat(p2p.contractViolations("listTransferPurposes", 200)).isEmpty();
        assertThat(p2p.body())
                .contains("FAMILY")
                .contains("GIFT")
                .doesNotContain("SOFTWARE")
                .doesNotContain("OLD");
        assertThat(api.get("/api/reference/transfer-purposes?partyTypePair=B2B", t).body())
                .contains("SOFTWARE")
                .contains("GIFT")
                .doesNotContain("FAMILY");
        assertThat(api.get("/api/reference/transfer-purposes", t).json().size())
                .as("sans filtre : toutes les valeurs actives")
                .isEqualTo(3);
        assertThat(api.get("/api/reference/transfer-purposes?partyTypePair=XYZ", t).status())
                .isIn(400, 422);
        assertThat(
                        api.get("/api/reference/sources-of-funds", t)
                                .contractViolations("listSourcesOfFunds", 200))
                .isEmpty();
        assertThat(api.get("/api/reference/relationships", t).json().get(0).get("label").asString())
                .isEqualTo("Frère");
        assertThat(api.get("/api/reference/relationships", null).status()).isEqualTo(401);
    }
}
