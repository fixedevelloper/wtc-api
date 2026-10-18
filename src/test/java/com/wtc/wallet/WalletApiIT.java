package com.wtc.wallet;

import static com.wtc.support.Api.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.identity.IdentityFixtures;
import com.wtc.identity.IdentityFixtures.Catalog;
import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.identity.domain.Role;
import com.wtc.support.AbstractIT;
import com.wtc.support.Api;
import com.wtc.support.Api.Reply;
import com.wtc.support.Sessions;
import com.wtc.support.Sessions.Session;
import com.wtc.support.TestClock;
import com.wtc.wallet.application.AccountRef;
import com.wtc.wallet.application.LedgerKind;
import com.wtc.wallet.application.LedgerService;
import com.wtc.wallet.application.PostingLeg;
import com.wtc.wallet.application.PostingRequest;
import com.wtc.wallet.application.WalletService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Routes des portefeuilles, ajustements à double validation et devis de transfert. */
class WalletApiIT extends AbstractIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;
    @Autowired TestClock clock;
    @Autowired LedgerService ledger;
    @Autowired WalletService wallets;

    static Catalog catalog;

    Api api;
    IdentityFixtures fx;

    @BeforeAll
    static void seed(
            @Autowired JdbcClient jdbc,
            @Autowired UserRepository users,
            @Autowired PasswordHasher hasher) {
        catalog = new IdentityFixtures(jdbc, users, hasher).seedCatalog();
    }

    @AfterAll
    static void clean(
            @Autowired JdbcClient jdbc,
            @Autowired UserRepository users,
            @Autowired PasswordHasher hasher) {
        jdbc.sql("DELETE FROM fee_schedules").update();
        jdbc.sql("DELETE FROM exchange_rates").update();
        jdbc.sql("UPDATE countries SET pricing_zone_id = NULL").update();
        jdbc.sql("DELETE FROM pricing_zones").update();
        new IdentityFixtures(jdbc, users, hasher).cleanUp();
    }

    @BeforeEach
    void init() {
        api = new Api(mvc);
        fx = new IdentityFixtures(jdbc, users, hasher);
        clock.reset();
    }

    private void credit(String customerId, String currency, String amount) {
        var wallet = wallets.ensureWallet(UUID.fromString(customerId), currency);
        BigDecimal value = new BigDecimal(amount);
        ledger.post(
                new PostingRequest(
                        "test:fund:" + UUID.randomUUID(),
                        LedgerKind.TOP_UP,
                        "Recharge de test",
                        List.of(
                                PostingLeg.debit(
                                        AccountRef.platform("PLATFORM_TOPUP_CLEARING"),
                                        value,
                                        currency),
                                PostingLeg.credit(AccountRef.wallet(wallet.id()), value, currency)),
                        null,
                        null,
                        null));
    }

    // ------------------------------------------------------------------ portefeuilles

    @Test
    void theMainWalletFollowsTheResidenceCurrencyAndEachCurrencyHasItsOwn() {
        Session me = Sessions.customer(api, fx, "client");
        credit(me.customerId(), "XAF", "12500");

        Reply main = api.get("/api/me/wallet", me.accessToken());
        assertThat(main.status()).isEqualTo(200);
        assertThat(main.contractViolations("getWallet", 200)).isEmpty();
        assertThat(main.text("currency")).as("ZZ a pour devise XAF").isEqualTo("XAF");
        assertThat(main.text("balance")).isEqualTo("12500.0000");

        Reply euro = api.get("/api/me/wallet?currency=EUR", me.accessToken());
        assertThat(euro.text("currency")).isEqualTo("EUR");
        assertThat(euro.text("balance")).isEqualTo("0.0000");
        assertThat(euro.text("id")).isNotEqualTo(main.text("id"));

        Reply all = api.get("/api/me/wallets", me.accessToken());
        assertThat(all.contractViolations("listWallets", 200)).isEmpty();
        assertThat(all.json().size()).isEqualTo(2);
        assertThat(api.get("/api/me/wallet?currency=euro", me.accessToken()).status())
                .isIn(400, 422);
    }

    @Test
    void theStatementListsMoneyInAndOutNewestFirstWithPaging() {
        Session me = Sessions.customer(api, fx, "client");
        credit(me.customerId(), "XAF", "1000");
        credit(me.customerId(), "XAF", "2000");
        credit(me.customerId(), "XAF", "3000");

        Reply page =
                api.get("/api/me/wallet/entries?size=2&sort=occurredAt,desc", me.accessToken());
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.contractViolations("listWalletEntries", 200)).isEmpty();
        assertThat(page.json().get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.json().get("content").size()).isEqualTo(2);
        var first = page.json().get("content").get(0);
        assertThat(first.get("direction").asString()).isEqualTo("CREDIT");
        assertThat(first.get("amount").get("amount").asString()).isEqualTo("3000.0000");
        assertThat(first.get("kind").asString()).isEqualTo("TOP_UP");
        assertThat(api.get("/api/me/wallet/entries?sort=amount,asc", me.accessToken()).status())
                .isEqualTo(422);
    }

    @Test
    void customersSeeOnlyTheirOwnWalletAndStaffSeeAnyThroughPermission() {
        Session alice = Sessions.customer(api, fx, "alice");
        Session bob = Sessions.customer(api, fx, "bob");
        credit(alice.customerId(), "XAF", "777");
        Session support = Sessions.staff(api, fx, clock, Role.SUPPORT);

        assertThat(api.get("/api/me/wallet", bob.accessToken()).text("balance"))
                .isEqualTo("0.0000");
        assertThat(api.get("/api/me/wallet", support.accessToken()).status())
                .as("le personnel n'a pas de portefeuille")
                .isEqualTo(403);

        Reply admin =
                api.get(
                        "/api/admin/customers/" + alice.customerId() + "/wallet",
                        support.accessToken());
        assertThat(admin.status()).isEqualTo(200);
        assertThat(admin.contractViolations("adminGetCustomerWallet", 200)).isEmpty();
        assertThat(admin.text("balance")).isEqualTo("777.0000");
        assertThat(
                        api.get(
                                        "/api/admin/customers/" + alice.customerId() + "/wallets",
                                        support.accessToken())
                                .contractViolations("adminListCustomerWallets", 200))
                .isEmpty();
        assertThat(
                        api.get(
                                        "/api/admin/customers/"
                                                + alice.customerId()
                                                + "/wallet/entries",
                                        support.accessToken())
                                .contractViolations("adminListCustomerWalletEntries", 200))
                .isEmpty();
        assertThat(
                        api.get(
                                        "/api/admin/customers/" + alice.customerId() + "/wallet",
                                        bob.accessToken())
                                .status())
                .as("un client n'ouvre pas le portefeuille d'un autre")
                .isEqualTo(403);
        assertThat(
                        api.get(
                                        "/api/admin/customers/" + UUID.randomUUID() + "/wallet",
                                        support.accessToken())
                                .code())
                .isEqualTo("CUSTOMER_NOT_FOUND");
        assertThat(
                        api.get(
                                        "/api/admin/customers/" + UUID.randomUUID() + "/wallets",
                                        support.accessToken())
                                .code())
                .isEqualTo("CUSTOMER_NOT_FOUND");
    }

    // ------------------------------------------------------------------ ajustements

    private Reply requestAdjustment(Session by, String key, Object body) {
        return post("/api/admin/balance-adjustments", body, by.accessToken(), key);
    }

    private Reply post(String path, Object body, String token, String idempotencyKey) {
        try {
            var builder =
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                            .header("Authorization", "Bearer " + token)
                            .contentType("application/json")
                            .content(
                                    new tools.jackson.databind.json.JsonMapper()
                                            .writeValueAsString(body));
            if (idempotencyKey != null) {
                builder.header("Idempotency-Key", idempotencyKey);
            }
            return new Reply(mvc.perform(builder).andReturn());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anAdjustmentMovesNoMoneyUntilAnotherPersonApproves() {
        Session customer = Sessions.customer(api, fx, "client");
        Session first = Sessions.staff(api, fx, clock, Role.ADMIN);
        Session second = Sessions.staff(api, fx, clock, Role.ADMIN);
        String key = "adj-" + UUID.randomUUID();
        var body =
                map(
                        "customerId",
                        customer.customerId(),
                        "direction",
                        "CREDIT",
                        "amount",
                        "5000",
                        "reason",
                        "Geste commercial");

        Reply created = requestAdjustment(first, key, body);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.contractViolations("adminRequestBalanceAdjustment", 201)).isEmpty();
        assertThat(created.text("status")).isEqualTo("PENDING");
        String id = created.text("id");
        assertThat(api.get("/api/me/wallet", customer.accessToken()).text("balance"))
                .as("rien n'a bougé")
                .isEqualTo("0.0000");

        Reply selfApproval =
                api.post(
                        "/api/admin/balance-adjustments/" + id + "/approve",
                        map(),
                        first.accessToken());
        assertThat(selfApproval.status()).isEqualTo(403);
        assertThat(selfApproval.code()).isEqualTo("FOUR_EYES_REQUIRED");
        assertThat(
                        api.post(
                                        "/api/admin/balance-adjustments/" + id + "/reject",
                                        map("reason", "Je me refuse"),
                                        first.accessToken())
                                .code())
                .as("pas non plus de refus par le demandeur")
                .isEqualTo("FOUR_EYES_REQUIRED");

        Reply approved =
                api.post(
                        "/api/admin/balance-adjustments/" + id + "/approve",
                        map("note", "OK"),
                        second.accessToken());
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.contractViolations("adminApproveBalanceAdjustment", 200)).isEmpty();
        assertThat(approved.text("status")).isEqualTo("APPROVED");
        assertThat(approved.text("decidedBy")).isEqualTo(second.email());
        assertThat(api.get("/api/me/wallet", customer.accessToken()).text("balance"))
                .isEqualTo("5000.0000");
        Reply entries = api.get("/api/me/wallet/entries", customer.accessToken());
        assertThat(entries.json().get("content").get(0).get("kind").asString())
                .isEqualTo("ADJUSTMENT");
        assertThat(
                        jdbc.sql("SELECT count(*) FROM wallet_balance_discrepancies")
                                .query(Long.class)
                                .single())
                .isZero();

        assertThat(
                        api.post(
                                        "/api/admin/balance-adjustments/" + id + "/approve",
                                        map(),
                                        second.accessToken())
                                .code())
                .isEqualTo("ADJUSTMENT_ALREADY_DECIDED");
        assertThat(api.get("/api/me/wallet", customer.accessToken()).text("balance"))
                .as("jamais deux fois")
                .isEqualTo("5000.0000");
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM audit_log WHERE entity_type ="
                                                + " 'BALANCE_ADJUSTMENT' AND entity_id = :id")
                                .param("id", id)
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
    }

    @Test
    void replayingARequestReturnsTheSameAdjustmentAndAChangedBodyIsRefused() {
        Session customer = Sessions.customer(api, fx, "client");
        Session admin = Sessions.staff(api, fx, clock, Role.ADMIN);
        String key = "adj-" + UUID.randomUUID();
        var body =
                map(
                        "customerId",
                        customer.customerId(),
                        "direction",
                        "CREDIT",
                        "amount",
                        "1000",
                        "reason",
                        "Remboursement manuel");

        String id = requestAdjustment(admin, key, body).text("id");
        Reply replay = requestAdjustment(admin, key, body);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.text("id")).isEqualTo(id);
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM balance_adjustments WHERE"
                                                + " idempotency_key = :k")
                                .param("k", key)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);

        Reply changed =
                requestAdjustment(
                        admin,
                        key,
                        map(
                                "customerId",
                                customer.customerId(),
                                "direction",
                                "CREDIT",
                                "amount",
                                "9000",
                                "reason",
                                "Remboursement manuel"));
        assertThat(changed.status()).isEqualTo(422);
        assertThat(changed.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(requestAdjustment(admin, null, body).status())
                .as("clé obligatoire")
                .isIn(400, 422);
        assertThat(requestAdjustment(admin, "court", body).status()).isIn(400, 422);
    }

    @Test
    void aDebitBeyondTheBalanceCannotBeApprovedAndStaysPendingUntilRejected() {
        Session customer = Sessions.customer(api, fx, "client");
        credit(customer.customerId(), "XAF", "1000");
        Session first = Sessions.staff(api, fx, clock, Role.ADMIN);
        Session second = Sessions.staff(api, fx, clock, Role.ADMIN);
        String id =
                requestAdjustment(
                                first,
                                "adj-" + UUID.randomUUID(),
                                map(
                                        "customerId",
                                        customer.customerId(),
                                        "direction",
                                        "DEBIT",
                                        "amount",
                                        "1500",
                                        "reason",
                                        "Erreur de saisie"))
                        .text("id");

        Reply refused =
                api.post(
                        "/api/admin/balance-adjustments/" + id + "/approve",
                        map(),
                        second.accessToken());
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(api.get("/api/me/wallet", customer.accessToken()).text("balance"))
                .isEqualTo("1000.0000");

        assertThat(
                        api.post(
                                        "/api/admin/balance-adjustments/" + id + "/reject",
                                        map("reason", "ok"),
                                        second.accessToken())
                                .status())
                .as("motif trop court")
                .isEqualTo(422);
        Reply rejected =
                api.post(
                        "/api/admin/balance-adjustments/" + id + "/reject",
                        map("reason", "Solde insuffisant pour ce débit"),
                        second.accessToken());
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.contractViolations("adminRejectBalanceAdjustment", 200)).isEmpty();
        assertThat(rejected.text("status")).isEqualTo("REJECTED");
        assertThat(
                        api.post(
                                        "/api/admin/balance-adjustments/" + id + "/approve",
                                        map(),
                                        second.accessToken())
                                .code())
                .isEqualTo("ADJUSTMENT_ALREADY_DECIDED");
    }

    @Test
    void adjustmentRequestsAreValidatedAndListed() {
        Session customer = Sessions.customer(api, fx, "client");
        Session admin = Sessions.staff(api, fx, clock, Role.ADMIN);
        String c = customer.customerId();

        assertThat(
                        requestAdjustment(
                                        admin,
                                        "k-" + UUID.randomUUID(),
                                        map(
                                                "customerId",
                                                c,
                                                "direction",
                                                "CREDIT",
                                                "amount",
                                                "10.5",
                                                "reason",
                                                "Décimales en XAF"))
                                .code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(
                        requestAdjustment(
                                        admin,
                                        "k-" + UUID.randomUUID(),
                                        map(
                                                "customerId",
                                                c,
                                                "direction",
                                                "CREDIT",
                                                "amount",
                                                "0",
                                                "reason",
                                                "Montant nul ici"))
                                .status())
                .isIn(400, 422);
        assertThat(
                        requestAdjustment(
                                        admin,
                                        "k-" + UUID.randomUUID(),
                                        map(
                                                "customerId",
                                                c,
                                                "direction",
                                                "SIDEWAYS",
                                                "amount",
                                                "10",
                                                "reason",
                                                "Mauvais sens"))
                                .status())
                .isIn(400, 422);
        assertThat(
                        requestAdjustment(
                                        admin,
                                        "k-" + UUID.randomUUID(),
                                        map(
                                                "customerId",
                                                c,
                                                "direction",
                                                "CREDIT",
                                                "amount",
                                                "10",
                                                "reason",
                                                "abc"))
                                .status())
                .isIn(400, 422);
        assertThat(
                        requestAdjustment(
                                        admin,
                                        "k-" + UUID.randomUUID(),
                                        map(
                                                "customerId",
                                                UUID.randomUUID().toString(),
                                                "direction",
                                                "CREDIT",
                                                "amount",
                                                "10",
                                                "reason",
                                                "Client fantôme"))
                                .code())
                .isEqualTo("CUSTOMER_NOT_FOUND");
        assertThat(
                        requestAdjustment(
                                        admin,
                                        "k-" + UUID.randomUUID(),
                                        map(
                                                "customerId",
                                                c,
                                                "direction",
                                                "CREDIT",
                                                "amount",
                                                "25.50",
                                                "currency",
                                                "EUR",
                                                "reason",
                                                "Dans une autre devise"))
                                .status())
                .isEqualTo(201);

        Reply list =
                api.get(
                        "/api/admin/balance-adjustments?search=autre&sort=createdAt,desc",
                        admin.accessToken());
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.contractViolations("adminListBalanceAdjustments", 200)).isEmpty();
        assertThat(list.json().get("totalElements").asLong()).isEqualTo(1);
        assertThat(list.json().get("content").get(0).get("amount").get("currency").asString())
                .isEqualTo("EUR");

        Reply pending =
                api.get("/api/admin/balance-adjustments?status=PENDING", admin.accessToken());
        assertThat(pending.status()).isEqualTo(200);
        assertThat(pending.json().get("totalElements").asLong()).isGreaterThanOrEqualTo(1);
        Reply approved =
                api.get("/api/admin/balance-adjustments?status=APPROVED", admin.accessToken());
        assertThat(approved.json().get("totalElements").asLong()).isZero();
        assertThat(
                        api.get("/api/admin/balance-adjustments?status=NOPE", admin.accessToken())
                                .status())
                .isEqualTo(422);
    }

    @Test
    void onlyThePermissionHoldersTouchAdjustments() {
        Session customer = Sessions.customer(api, fx, "client");
        Session support = Sessions.staff(api, fx, clock, Role.SUPPORT);
        var body =
                map(
                        "customerId",
                        customer.customerId(),
                        "direction",
                        "CREDIT",
                        "amount",
                        "10",
                        "reason",
                        "Essai non autorisé");
        assertThat(requestAdjustment(support, "k-" + UUID.randomUUID(), body).status())
                .isEqualTo(403);
        assertThat(api.get("/api/admin/balance-adjustments", support.accessToken()).status())
                .isEqualTo(403);
        assertThat(api.get("/api/admin/balance-adjustments", customer.accessToken()).status())
                .isEqualTo(403);
        assertThat(
                        api.post(
                                        "/api/admin/balance-adjustments/"
                                                + UUID.randomUUID()
                                                + "/approve",
                                        map(),
                                        support.accessToken())
                                .status())
                .isEqualTo(403);
        Session admin = Sessions.staff(api, fx, clock, Role.ADMIN);
        assertThat(
                        api.post(
                                        "/api/admin/balance-adjustments/"
                                                + UUID.randomUUID()
                                                + "/approve",
                                        map(),
                                        admin.accessToken())
                                .code())
                .isEqualTo("ADJUSTMENT_NOT_FOUND");
    }

    // ------------------------------------------------------------------ devis

    private String beneficiaryDestination(Session me) {
        String beneficiary =
                api.post(
                                "/api/me/beneficiaries",
                                map(
                                        "kind",
                                        "INDIVIDUAL",
                                        "firstName",
                                        "Awa",
                                        "lastName",
                                        "Traoré",
                                        "countryCode",
                                        "YY"),
                                me.accessToken())
                        .text("id");
        return api.post(
                        "/api/me/beneficiaries/" + beneficiary + "/destinations",
                        map(
                                "kind",
                                "BANK_ACCOUNT",
                                "countryCode",
                                "YY",
                                "bankId",
                                catalog.bankYy(),
                                "accountNumber",
                                "12345678"),
                        me.accessToken())
                .text("id");
    }

    private void priceTheCorridor() {
        jdbc.sql("DELETE FROM fee_schedules").update();
        jdbc.sql("DELETE FROM exchange_rates").update();
        jdbc.sql("UPDATE countries SET pricing_zone_id = NULL").update();
        jdbc.sql("DELETE FROM pricing_zones").update();
        long zone =
                jdbc.sql(
                                "INSERT INTO pricing_zones (code, name) VALUES ('Z-QUOTE', 'Zone"
                                        + " devis') RETURNING id")
                        .query(Long.class)
                        .single();
        jdbc.sql("UPDATE countries SET pricing_zone_id = :z WHERE code = 'YY'")
                .param("z", zone)
                .update();
        jdbc.sql(
                        "INSERT INTO fee_schedules (pricing_zone_id, currency_code, min_amount,"
                            + " max_amount, percent, fixed_fee, effective_from) VALUES (:z, 'XAF',"
                            + " 0, 1000000, 5, 0, :d)")
                .param("z", zone)
                .param("d", java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC).minusDays(1)))
                .update();
        jdbc.sql(
                        "INSERT INTO exchange_rates (base_currency, quote_currency, rate,"
                            + " effective_from) VALUES ('XAF', 'XOF', 1, now() - interval '1 day')")
                .update();
    }

    @Test
    void aQuoteGivesFeeTotalReceivedAmountAndWhetherTheWalletCoversIt() {
        priceTheCorridor();
        Session me = Sessions.customer(api, fx, "client");
        String destination = beneficiaryDestination(me);
        credit(me.customerId(), "XAF", "10500");

        Reply enough =
                api.post(
                        "/api/me/transfers/quote",
                        map("destinationId", destination, "sourceAmount", "10000"),
                        me.accessToken());
        assertThat(enough.status()).isEqualTo(200);
        assertThat(enough.contractViolations("quoteTransfer", 200)).isEmpty();
        assertThat(enough.json().get("fee").get("amount").asString()).isEqualTo("500");
        assertThat(enough.json().get("totalDebit").get("amount").asString()).isEqualTo("10500");
        assertThat(enough.json().get("destinationAmount").get("currency").asString())
                .isEqualTo("XOF");
        assertThat(enough.json().get("availableBalance").get("amount").asString())
                .isEqualTo("10500.0000");
        assertThat(enough.json().get("sufficientFunds").asBoolean()).isTrue();

        Reply short_ =
                api.post(
                        "/api/me/transfers/quote",
                        map("destinationId", destination, "sourceAmount", "10001"),
                        me.accessToken());
        assertThat(short_.json().get("sufficientFunds").asBoolean())
                .as("10 001 + 5 % = 10 501 > 10 500")
                .isFalse();
        assertThat(api.get("/api/me/wallet", me.accessToken()).text("balance"))
                .as("un devis ne bouge rien")
                .isEqualTo("10500.0000");
    }

    @Test
    void aQuoteRefusesWhatItCannotPrice() {
        priceTheCorridor();
        Session me = Sessions.customer(api, fx, "client");
        Session other = Sessions.customer(api, fx, "autre");
        String destination = beneficiaryDestination(me);

        assertThat(
                        api.post(
                                        "/api/me/transfers/quote",
                                        map("destinationId", destination, "sourceAmount", "10"),
                                        other.accessToken())
                                .code())
                .as("destination d'un autre client")
                .isEqualTo("DESTINATION_NOT_FOUND");
        assertThat(
                        api.post(
                                        "/api/me/transfers/quote",
                                        map(
                                                "destinationId",
                                                UUID.randomUUID().toString(),
                                                "sourceAmount",
                                                "10"),
                                        me.accessToken())
                                .status())
                .isEqualTo(404);
        assertThat(
                        api.post(
                                        "/api/me/transfers/quote",
                                        map(
                                                "destinationId",
                                                destination,
                                                "sourceAmount",
                                                "5000000"),
                                        me.accessToken())
                                .code())
                .isEqualTo("FEE_NOT_CONFIGURED");
        assertThat(
                        api.post(
                                        "/api/me/transfers/quote",
                                        map("destinationId", destination, "sourceAmount", "10.5"),
                                        me.accessToken())
                                .code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(
                        api.post(
                                        "/api/me/transfers/quote",
                                        map("destinationId", destination, "sourceAmount", "-1"),
                                        me.accessToken())
                                .status())
                .isIn(400, 422);
        jdbc.sql("DELETE FROM exchange_rates").update();
        assertThat(
                        api.post(
                                        "/api/me/transfers/quote",
                                        map("destinationId", destination, "sourceAmount", "100"),
                                        me.accessToken())
                                .code())
                .isEqualTo("EXCHANGE_RATE_UNAVAILABLE");
    }

    @Test
    void staffQuoteOnBehalfOfACustomerNeedsTheTransferCreatePermission() {
        priceTheCorridor();
        Session me = Sessions.customer(api, fx, "client");
        String destination = beneficiaryDestination(me);
        credit(me.customerId(), "XAF", "100000");
        Session admin = Sessions.staff(api, fx, clock, Role.ADMIN);
        Session support = Sessions.staff(api, fx, clock, Role.SUPPORT);

        Reply quote =
                api.post(
                        "/api/admin/transfers/quote",
                        map(
                                "destinationId",
                                destination,
                                "sourceAmount",
                                "20000",
                                "customerId",
                                me.customerId()),
                        admin.accessToken());
        assertThat(quote.status()).isEqualTo(200);
        assertThat(quote.contractViolations("adminQuoteTransfer", 200)).isEmpty();
        assertThat(quote.json().get("totalDebit").get("amount").asString()).isEqualTo("21000");
        assertThat(
                        api.post(
                                        "/api/admin/transfers/quote",
                                        map(
                                                "destinationId",
                                                destination,
                                                "sourceAmount",
                                                "20000",
                                                "customerId",
                                                me.customerId()),
                                        support.accessToken())
                                .status())
                .isEqualTo(403);
        assertThat(
                        api.post(
                                        "/api/admin/transfers/quote",
                                        map(
                                                "destinationId",
                                                destination,
                                                "sourceAmount",
                                                "20000",
                                                "customerId",
                                                me.customerId()),
                                        me.accessToken())
                                .status())
                .isEqualTo(403);
        assertThat(
                        api.post(
                                        "/api/me/transfers/quote",
                                        map("destinationId", destination, "sourceAmount", "20000"),
                                        admin.accessToken())
                                .status())
                .as("le personnel n'a pas de devis client")
                .isEqualTo(403);
    }
}
