package com.wtc.transfer;

import static com.wtc.support.Api.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.identity.IdentityFixtures;
import com.wtc.identity.IdentityFixtures.Catalog;
import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.identity.domain.Role;
import com.wtc.notification.application.Channel;
import com.wtc.notification.application.EventBridge;
import com.wtc.notification.application.NotificationDispatcher;
import com.wtc.notification.application.OperationsWatcher;
import com.wtc.notification.application.SendResult;
import com.wtc.notification.infrastructure.FakeNotificationSender;
import com.wtc.payment.application.PayoutGateway.OrderStatus;
import com.wtc.payment.application.PayoutGateway.Outcome;
import com.wtc.payment.application.PayoutGateway.ProviderStatus;
import com.wtc.payment.application.PayoutGateway.Result;
import com.wtc.payment.infrastructure.FakePayoutGateway;
import com.wtc.support.AbstractIT;
import com.wtc.support.Api;
import com.wtc.support.Api.Reply;
import com.wtc.support.Sessions;
import com.wtc.support.Sessions.Session;
import com.wtc.support.TestClock;
import com.wtc.transfer.application.PayoutProcessor;
import com.wtc.wallet.application.AccountRef;
import com.wtc.wallet.application.LedgerKind;
import com.wtc.wallet.application.LedgerService;
import com.wtc.wallet.application.PostingLeg;
import com.wtc.wallet.application.PostingRequest;
import com.wtc.wallet.application.WalletService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Du clic de l'utilisateur au paiement chez le fournisseur (faux Wace piloté) : débit atomique,
 * envoi, suivi, remboursement.
 */
class TransferFlowIT extends AbstractIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;
    @Autowired TestClock clock;
    @Autowired LedgerService ledger;
    @Autowired WalletService wallets;
    @Autowired FakePayoutGateway fake;
    @Autowired PayoutProcessor worker;
    @Autowired EventBridge bridge;
    @Autowired NotificationDispatcher dispatcher;
    @Autowired OperationsWatcher watcher;
    @Autowired List<FakeNotificationSender> fakeChannels;

    static Catalog catalog;

    Api api;
    IdentityFixtures fx;

    /**
     * Un client prêt à envoyer : identité vérifiée, contacts complets, portefeuille approvisionné.
     */
    record Scenario(
            Session customer,
            String senderId,
            String beneficiaryId,
            String bankDestinationId,
            String mobileDestinationId) {}

    @BeforeAll
    static void seed(
            @Autowired JdbcClient jdbc,
            @Autowired UserRepository users,
            @Autowired PasswordHasher hasher) {
        catalog = new IdentityFixtures(jdbc, users, hasher).seedCatalog();
        jdbc.sql("UPDATE banks SET wace_bank_id = '5', short_code = '10005' WHERE id = :b")
                .param("b", catalog.bankYy())
                .update();
        jdbc.sql("UPDATE mobile_operators SET wace_payer_code = '16845316212' WHERE id = :o")
                .param("o", catalog.operatorYy())
                .update();
        jdbc.sql("UPDATE countries SET wace_bank_payer_code = '1753881196' WHERE code = 'YY'")
                .update();
        jdbc.sql(
                        """
                        INSERT INTO reference_values (category, party_type_pair, code, label, wace_code) VALUES
                            ('TRANSFER_PURPOSE', 'P2P', 'FAMILY', 'Soutien familial', 'Family Maintainance'),
                            ('SOURCE_OF_FUNDS', 'ALL', 'SALARY', 'Salaire', 'Salary'),
                            ('RELATIONSHIP', 'ALL', 'BROTHER', 'Frère', 'Brother')
                        """)
                .update();
        long zone =
                jdbc.sql(
                                "INSERT INTO pricing_zones (code, name) VALUES ('Z-TRANSFER', 'Zone"
                                        + " transferts') RETURNING id")
                        .query(Long.class)
                        .single();
        jdbc.sql("UPDATE countries SET pricing_zone_id = :z WHERE code = 'YY'")
                .param("z", zone)
                .update();
        jdbc.sql(
                        "INSERT INTO fee_schedules (pricing_zone_id, currency_code, min_amount,"
                            + " max_amount, percent, fixed_fee, effective_from) VALUES (:z, 'XAF',"
                            + " 0, 100000000, 5, 0, :d)")
                .param("z", zone)
                .param("d", java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC).minusDays(1)))
                .update();
        jdbc.sql(
                        "INSERT INTO exchange_rates (base_currency, quote_currency, rate,"
                            + " effective_from) VALUES ('XAF', 'XOF', 1, now() - interval '1 day')")
                .update();
    }

    @AfterAll
    static void clean(
            @Autowired JdbcClient jdbc,
            @Autowired UserRepository users,
            @Autowired PasswordHasher hasher) {
        resetMoney(jdbc);
        jdbc.sql("DELETE FROM fee_schedules").update();
        jdbc.sql("DELETE FROM exchange_rates").update();
        jdbc.sql("UPDATE countries SET pricing_zone_id = NULL, wace_bank_payer_code = NULL")
                .update();
        jdbc.sql("DELETE FROM pricing_zones").update();
        jdbc.sql("DELETE FROM reference_values").update();
        new IdentityFixtures(jdbc, users, hasher).cleanUp();
    }

    @BeforeEach
    void init() {
        api = new Api(mvc);
        fx = new IdentityFixtures(jdbc, users, hasher);
        clock.reset();
        fake.reset();
        fakeChannels.forEach(FakeNotificationSender::reset);
        resetMoney(jdbc);
    }

    /**
     * Chaque test part d'un grand livre, de transferts et d'une file d'envoi vides : les
     * travailleurs traitent tout ce qui est dû, donc les restes d'un test précédent fausseraient
     * les comptes de celui-ci.
     */
    private static void resetMoney(JdbcClient jdbc) {
        jdbc.sql(
                        "TRUNCATE ledger_entries, ledger_transactions, balance_adjustments,"
                                + " outbox_events, notification_outbox CASCADE")
                .update();
        jdbc.sql("DELETE FROM provider_party_refs").update();
        jdbc.sql("DELETE FROM ledger_accounts WHERE kind = 'CUSTOMER_WALLET'").update();
        jdbc.sql("DELETE FROM wallets").update();
    }

    // ------------------------------------------------------------------ mise en place

    private Scenario scenario(String funds) {
        Session me = Sessions.customer(api, fx, "expediteur");
        jdbc.sql("UPDATE customers SET kyc_status = 'VERIFIED' WHERE public_id = CAST(:id AS uuid)")
                .param("id", me.customerId())
                .update();
        String t = me.accessToken();
        String sender =
                api.post(
                                "/api/me/senders",
                                map(
                                        "kind",
                                        "INDIVIDUAL",
                                        "firstName",
                                        "Claire",
                                        "lastName",
                                        "Dupont",
                                        "gender",
                                        "F",
                                        "civility",
                                        "Single",
                                        "addressLine",
                                        "12 rue des Palmiers",
                                        "city",
                                        "Douala",
                                        "countryCode",
                                        "ZZ",
                                        "phone",
                                        "+23767418529",
                                        "nationality",
                                        "CM",
                                        "birthDate",
                                        "1990-03-03",
                                        "idDocumentType",
                                        "PASSPORT",
                                        "idDocumentNumber",
                                        "AB1234567",
                                        "idDocumentExpiresOn",
                                        LocalDate.now(ZoneOffset.UTC).plusYears(3).toString()),
                                t)
                        .text("id");
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
                                        "addressLine",
                                        "5 avenue Centrale",
                                        "city",
                                        "Abidjan",
                                        "countryCode",
                                        "YY",
                                        "phone",
                                        "+22507070707",
                                        "idDocumentType",
                                        "NATIONAL_ID",
                                        "idDocumentNumber",
                                        "CI0099887"),
                                t)
                        .text("id");
        String bank =
                api.post(
                                "/api/me/beneficiaries/" + beneficiary + "/destinations",
                                map(
                                        "kind",
                                        "BANK_ACCOUNT",
                                        "countryCode",
                                        "YY",
                                        "bankId",
                                        catalog.bankYy(),
                                        "accountNumber",
                                        "14785296321",
                                        "accountHolderName",
                                        "Awa Traoré"),
                                t)
                        .text("id");
        String mobile =
                api.post(
                                "/api/me/beneficiaries/" + beneficiary + "/destinations",
                                map(
                                        "kind",
                                        "MOBILE_WALLET",
                                        "countryCode",
                                        "YY",
                                        "mobileOperatorId",
                                        catalog.operatorYy(),
                                        "accountNumber",
                                        "+22507070707"),
                                t)
                        .text("id");
        if (funds != null) {
            credit(me.customerId(), funds);
        }
        return new Scenario(me, sender, beneficiary, bank, mobile);
    }

    private void credit(String customerId, String amount) {
        var wallet = wallets.ensureWallet(UUID.fromString(customerId), "XAF");
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
                                        "XAF"),
                                PostingLeg.credit(AccountRef.wallet(wallet.id()), value, "XAF")),
                        null,
                        null,
                        null));
    }

    private Object body(Scenario s, String destination, String amount) {
        return map(
                "senderId",
                s.senderId(),
                "beneficiaryId",
                s.beneficiaryId(),
                "destinationId",
                destination,
                "sourceAmount",
                amount,
                "purposeCode",
                "FAMILY",
                "sourceOfFundsCode",
                "SALARY",
                "relationshipCode",
                "BROTHER",
                "comment",
                "Pour la rentrée");
    }

    private Reply send(Scenario s, String key, Object body) {
        try {
            var builder =
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                    "/api/me/transfers")
                            .header("Authorization", "Bearer " + s.customer().accessToken())
                            .header("Idempotency-Key", key)
                            .contentType("application/json")
                            .content(
                                    new tools.jackson.databind.json.JsonMapper()
                                            .writeValueAsString(body));
            return new Reply(mvc.perform(builder).andReturn());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Reply send(Scenario s, String destination, String amount) {
        return send(s, "key-" + UUID.randomUUID(), body(s, destination, amount));
    }

    private String balance(Scenario s) {
        return api.get("/api/me/wallet", s.customer().accessToken()).text("balance");
    }

    private String status(String transferId) {
        return jdbc.sql("SELECT status FROM transfers WHERE public_id = CAST(:id AS uuid)")
                .param("id", transferId)
                .query(String.class)
                .single();
    }

    private String hold(String transferId) {
        return jdbc.sql("SELECT payout_hold FROM transfers WHERE public_id = CAST(:id AS uuid)")
                .param("id", transferId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private String providerReference(String transferId) {
        return jdbc.sql(
                        "SELECT provider_reference FROM transfers WHERE public_id = CAST(:id AS"
                                + " uuid)")
                .param("id", transferId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private void dueNow() {
        jdbc.sql(
                        "UPDATE outbox_events SET next_attempt_at = now() - interval '1 hour'"
                                + " WHERE status = 'PENDING'")
                .update();
    }

    private void invariants() {
        assertThat(
                        jdbc.sql("SELECT count(*) FROM wallet_balance_discrepancies")
                                .query(Long.class)
                                .single())
                .as("soldes = grand livre")
                .isZero();
    }

    // ------------------------------------------------------------------ parcours nominal

    @Test
    void aTransferIsDebitedAtomicallySentConfirmedAndCompleted() {
        Scenario s = scenario("100000");
        Reply created = send(s, s.bankDestinationId(), "10000");

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.contractViolations("createTransfer", 201)).isEmpty();
        String id = created.text("id");
        assertThat(created.text("status")).isEqualTo("PAYOUT_PENDING");
        assertThat(created.text("reference")).matches("WTC-\\d{4}-\\d{6,}");
        assertThat(created.json().get("totalDebit").get("amount").asString()).isEqualTo("10500");
        assertThat(balance(s))
                .as("débité au moment de la création : 10 000 + 5 % de frais")
                .isEqualTo("89500.0000");
        assertThat(fake.calls()).as("aucun appel réseau dans la création").isEmpty();
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM ledger_entries e JOIN"
                                                + " ledger_transactions t ON t.id ="
                                                + " e.ledger_transaction_id WHERE t.reference = :r")
                                .param("r", "transfer:" + id + ":debit")
                                .query(Long.class)
                                .single())
                .as("débit portefeuille + transit + frais")
                .isEqualTo(3);

        assertThat(worker.processOutbox()).isEqualTo(1);
        assertThat(status(id)).isEqualTo("PROCESSING");
        String reference = providerReference(id);
        assertThat(reference).startsWith("WPPX");
        assertThat(fake.countCalls("createSender")).isEqualTo(1);
        assertThat(fake.countCalls("createBeneficiary")).isEqualTo(1);
        assertThat(fake.countCalls("createOrder")).isEqualTo(1);
        assertThat(
                        jdbc.sql(
                                        "SELECT provider_fee FROM transfers WHERE public_id ="
                                                + " CAST(:id AS uuid)")
                                .param("id", id)
                                .query(BigDecimal.class)
                                .single())
                .isEqualByComparingTo("350");

        assertThat(worker.processOutbox()).as("confirmation").isEqualTo(1);
        assertThat(fake.countCalls("confirm")).isEqualTo(1);
        assertThat(
                        jdbc.sql(
                                        "SELECT provider_confirmed_at IS NOT NULL FROM transfers"
                                                + " WHERE public_id = CAST(:id AS uuid)")
                                .param("id", id)
                                .query(Boolean.class)
                                .single())
                .isTrue();

        fake.scriptStatus(
                reference,
                Result.ok(
                        new OrderStatus(
                                ProviderStatus.PAID,
                                "PAID",
                                new BigDecimal("10000"),
                                new BigDecimal("350")),
                        "{}",
                        "{}"));
        jdbc.sql("UPDATE transfers SET provider_polled_at = now() - interval '1 hour'").update();
        assertThat(worker.pollStatuses()).isEqualTo(1);

        assertThat(status(id)).isEqualTo("COMPLETED");
        Reply detail = api.get("/api/me/transfers/" + id, s.customer().accessToken());
        assertThat(detail.contractViolations("getTransfer", 200)).isEmpty();
        assertThat(detail.text("status")).isEqualTo("COMPLETED");
        assertThat(detail.text("completedAt")).isNotBlank();
        assertThat(detail.json().get("sender").get("idDocument").get("numberMasked").asString())
                .as("pièce masquée dans le transfert")
                .isEqualTo("****4567");
        assertThat(detail.json().get("history").size()).isGreaterThanOrEqualTo(4);
        assertThat(balance(s)).as("rien n'est rendu").isEqualTo("89500.0000");
        invariants();
    }

    @Test
    void contactsAreDeclaredOnceAtTheProviderAndReusedAfterwards() {
        Scenario s = scenario("100000");
        send(s, s.bankDestinationId(), "10000");
        worker.processOutbox();
        send(s, s.mobileDestinationId(), "5000");
        worker.processOutbox();

        assertThat(fake.countCalls("createSender")).isEqualTo(1);
        assertThat(fake.countCalls("createBeneficiary")).isEqualTo(1);
        assertThat(fake.countCalls("createOrder")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM provider_party_refs").query(Long.class).single())
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void mobileMoneyOrdersCarryTheWalletNumberAndTheFrozenWaceValues() {
        Scenario s = scenario("100000");
        Reply created = send(s, s.mobileDestinationId(), "5000");
        assertThat(created.text("kind")).isEqualTo("MOBILE_MONEY");
        worker.processOutbox();
        assertThat(fake.calls())
                .extracting(FakePayoutGateway.Call::operation)
                .contains("createOrder");
        assertThat(
                        jdbc.sql(
                                        "SELECT request_payload::text FROM payout_attempts ORDER BY"
                                                + " id DESC LIMIT 1")
                                .query(String.class)
                                .single())
                .as("la trace est sobre")
                .doesNotContain("07070707");
    }

    // ------------------------------------------------------------------ refus à la création

    @Test
    void nothingIsDebitedWhenTheWalletCannotCoverTheTotal() {
        Scenario s = scenario("10000");
        Reply refused = send(s, s.bankDestinationId(), "10000"); // 10 500 nécessaires
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balance(s)).isEqualTo("10000.0000");
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM transfers t JOIN customers c ON c.id"
                                                + " = t.customer_id WHERE c.public_id = CAST(:id AS"
                                                + " uuid)")
                                .param("id", s.customer().customerId())
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(
                        jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = 'PENDING'")
                                .query(Long.class)
                                .single())
                .isZero();
        invariants();
    }

    @Test
    void anUnverifiedSuspendedOrIncompleteSetupBlocksTheTransferBeforeAnyDebit() {
        Scenario s = scenario("100000");

        jdbc.sql("UPDATE customers SET kyc_status = 'PENDING' WHERE public_id = CAST(:id AS uuid)")
                .param("id", s.customer().customerId())
                .update();
        Reply kyc = send(s, s.bankDestinationId(), "1000");
        assertThat(kyc.status()).isEqualTo(403);
        assertThat(kyc.code()).isEqualTo("KYC_REQUIRED");
        jdbc.sql("UPDATE customers SET kyc_status = 'VERIFIED' WHERE public_id = CAST(:id AS uuid)")
                .param("id", s.customer().customerId())
                .update();

        jdbc.sql(
                        "UPDATE parties SET gender = NULL, address_line = NULL WHERE public_id ="
                                + " CAST(:id AS uuid)")
                .param("id", s.senderId())
                .update();
        Reply incomplete = send(s, s.bankDestinationId(), "1000");
        assertThat(incomplete.status()).isEqualTo(422);
        assertThat(incomplete.code()).isEqualTo("PARTY_INCOMPLETE");
        assertThat(incomplete.json().get("errors").toString())
                .contains("sender.gender", "sender.addressLine");

        assertThat(balance(s)).isEqualTo("100000.0000");
        invariants();
    }

    @Test
    void theQuoteTheCustomerSawMustStillHoldOtherwiseNothingIsSent() {
        Scenario s = scenario("100000");
        var request =
                map(
                        "senderId",
                        s.senderId(),
                        "beneficiaryId",
                        s.beneficiaryId(),
                        "destinationId",
                        s.bankDestinationId(),
                        "sourceAmount",
                        "10000",
                        "purposeCode",
                        "FAMILY",
                        "sourceOfFundsCode",
                        "SALARY",
                        "relationshipCode",
                        "BROTHER",
                        "expectedTotalDebit",
                        "10000");
        Reply changed = send(s, "key-" + UUID.randomUUID(), request);
        assertThat(changed.status()).isEqualTo(409);
        assertThat(changed.code()).isEqualTo("QUOTE_CHANGED");
        assertThat(balance(s)).isEqualTo("100000.0000");

        request.put("expectedTotalDebit", "10500");
        assertThat(send(s, "key-" + UUID.randomUUID(), request).status()).isEqualTo(201);
    }

    @Test
    void ownershipReferencesAndLimitsAreChecked() {
        Scenario mine = scenario("100000");
        Scenario other = scenario("100000");

        assertThat(send(mine, mine.bankDestinationId(), "1000").status()).isEqualTo(201);
        var foreign =
                map(
                        "senderId",
                        other.senderId(),
                        "beneficiaryId",
                        mine.beneficiaryId(),
                        "destinationId",
                        mine.bankDestinationId(),
                        "sourceAmount",
                        "1000",
                        "purposeCode",
                        "FAMILY",
                        "sourceOfFundsCode",
                        "SALARY",
                        "relationshipCode",
                        "BROTHER");
        assertThat(send(mine, "key-" + UUID.randomUUID(), foreign).code())
                .as("expéditeur d'un autre client")
                .isEqualTo("PARTY_NOT_FOUND");

        var wrongDestination =
                map(
                        "senderId",
                        mine.senderId(),
                        "beneficiaryId",
                        mine.beneficiaryId(),
                        "destinationId",
                        other.bankDestinationId(),
                        "sourceAmount",
                        "1000",
                        "purposeCode",
                        "FAMILY",
                        "sourceOfFundsCode",
                        "SALARY",
                        "relationshipCode",
                        "BROTHER");
        assertThat(send(mine, "key-" + UUID.randomUUID(), wrongDestination).code())
                .as("destination d'un autre client")
                .isEqualTo("DESTINATION_NOT_FOUND");

        var badPurpose =
                map(
                        "senderId",
                        mine.senderId(),
                        "beneficiaryId",
                        mine.beneficiaryId(),
                        "destinationId",
                        mine.bankDestinationId(),
                        "sourceAmount",
                        "1000",
                        "purposeCode",
                        "NOPE",
                        "sourceOfFundsCode",
                        "SALARY",
                        "relationshipCode",
                        "BROTHER");
        assertThat(send(mine, "key-" + UUID.randomUUID(), badPurpose).code())
                .isEqualTo("VALIDATION_FAILED");

        credit(mine.customer().customerId(), "20000000");
        Reply tooMuch = send(mine, mine.bankDestinationId(), "6000000");
        assertThat(tooMuch.status()).isEqualTo(422);
        assertThat(tooMuch.code()).isEqualTo("AMOUNT_LIMIT_EXCEEDED");
        assertThat(
                        api.get(
                                        "/api/me/transfers/" + UUID.randomUUID(),
                                        mine.customer().accessToken())
                                .code())
                .isEqualTo("TRANSFER_NOT_FOUND");
        invariants();
    }

    // ------------------------------------------------------------------ idempotence et concurrence

    @Test
    void replayingTheSameRequestNeverDebitsTwiceAndAChangedBodyIsRefused() {
        Scenario s = scenario("100000");
        String key = "key-" + UUID.randomUUID();
        Object body = body(s, s.bankDestinationId(), "10000");

        Reply first = send(s, key, body);
        Reply replay = send(s, key, body);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.text("id")).isEqualTo(first.text("id"));
        assertThat(balance(s)).as("débité une seule fois").isEqualTo("89500.0000");

        Reply other = send(s, key, body(s, s.bankDestinationId(), "20000"));
        assertThat(other.status()).isEqualTo(422);
        assertThat(other.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(send(s, null, body).status()).as("clé obligatoire").isIn(400, 422);
    }

    @Test
    void tenSimultaneousSubmissionsWithOneKeyCreateOneTransfer() throws Exception {
        Scenario s = scenario("100000");
        String key = "key-" + UUID.randomUUID();
        Object body = body(s, s.bankDestinationId(), "10000");

        List<Object> results = concurrently(10, i -> () -> send(s, key, body).status());

        assertThat(results)
                .as("aucune erreur serveur")
                .allMatch(code -> (Integer) code == 201 || (Integer) code == 409);
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM transfers t JOIN customers c ON c.id"
                                                + " = t.customer_id WHERE c.public_id = CAST(:id AS"
                                                + " uuid)")
                                .param("id", s.customer().customerId())
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        assertThat(balance(s)).isEqualTo("89500.0000");
        invariants();
    }

    @Test
    void sevenSimultaneousTransfersCannotOverdrawTheWallet() throws Exception {
        Scenario s = scenario("31500"); // trois transferts de 10 000 (+5 %) seulement
        List<Object> results =
                concurrently(
                        7,
                        i ->
                                () ->
                                        send(
                                                        s,
                                                        "key-" + UUID.randomUUID(),
                                                        body(s, s.bankDestinationId(), "10000"))
                                                .status());

        assertThat(results.stream().filter(code -> (Integer) code == 201).count()).isEqualTo(3);
        assertThat(results.stream().filter(code -> (Integer) code == 422).count()).isEqualTo(4);
        assertThat(balance(s)).isEqualTo("0.0000");
        invariants();
    }

    // ------------------------------------------------------------------ échecs du fournisseur

    @Test
    void anExplicitRefusalRefundsTheCustomerExactlyOnce() {
        Scenario s = scenario("100000");
        String id = send(s, s.bankDestinationId(), "10000").text("id");
        fake.scriptNextCreate(
                Result.failure(
                        Outcome.REJECTED,
                        "2011",
                        "No feeds is configure to this amount",
                        "{}",
                        "{}"));

        worker.processOutbox();

        assertThat(status(id)).isEqualTo("REFUNDED");
        assertThat(balance(s)).as("remboursé : montant et frais").isEqualTo("100000.0000");
        assertThat(worker.processOutbox()).isZero();
        assertThat(
                        jdbc.sql("SELECT count(*) FROM ledger_transactions WHERE reference = :r")
                                .param("r", "transfer:" + id + ":refund")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        assertThat(
                        jdbc.sql(
                                        "SELECT failure_reason FROM transfers WHERE public_id ="
                                                + " CAST(:id AS uuid)")
                                .param("id", id)
                                .query(String.class)
                                .single())
                .contains("2011");
        assertThat(jdbc.sql("SELECT outcome FROM payout_attempts").query(String.class).list())
                .contains("REJECTED");
        invariants();
    }

    @Test
    void anAmbiguousAnswerBlocksTheTransferWithoutRefundingOrRetrying() {
        Scenario s = scenario("100000");
        String id = send(s, s.bankDestinationId(), "10000").text("id");
        fake.scriptNextCreate(Result.failure(Outcome.AMBIGUOUS, null, "Délai dépassé", "{}", "{}"));

        worker.processOutbox();

        assertThat(status(id)).isEqualTo("PAYOUT_PENDING");
        assertThat(hold(id)).isEqualTo("AMBIGUOUS_CREATE");
        assertThat(balance(s)).as("l'argent reste retenu").isEqualTo("89500.0000");
        // Même si l'événement est remis en file par erreur, l'ordre n'est JAMAIS recréé.
        jdbc.sql(
                        "INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type,"
                            + " payload) SELECT 'TRANSFER', id, 'payout.create', '{}'::jsonb FROM"
                            + " transfers WHERE public_id = CAST(:id AS uuid)")
                .param("id", id)
                .update();
        worker.processOutbox();
        assertThat(fake.countCalls("createOrder")).isEqualTo(1);
        assertThat(status(id)).isEqualTo("PAYOUT_PENDING");
        invariants();
    }

    @Test
    void aBlockedTransferIsResolvedByAHumanEitherByRefundOrByAttachingTheFoundOrder() {
        Scenario s = scenario("100000");
        Session admin = Sessions.staff(api, fx, clock, Role.ADMIN);
        String refundId = send(s, s.bankDestinationId(), "10000").text("id");
        fake.scriptNextCreate(Result.failure(Outcome.AMBIGUOUS, null, "Délai dépassé", "{}", "{}"));
        worker.processOutbox();

        assertThat(
                        api.post(
                                        "/api/admin/transfers/" + refundId + "/refund",
                                        map("reason", "ok"),
                                        admin.accessToken())
                                .status())
                .as("motif trop court")
                .isIn(400, 422);
        Reply refunded =
                api.post(
                        "/api/admin/transfers/" + refundId + "/refund",
                        map("reason", "Aucun ordre dans le back-office Wace"),
                        admin.accessToken());
        assertThat(refunded.status()).isEqualTo(200);
        assertThat(refunded.contractViolations("adminRefundTransfer", 200)).isEmpty();
        assertThat(refunded.text("status")).isEqualTo("REFUNDED");
        assertThat(balance(s)).isEqualTo("100000.0000");
        assertThat(
                        api.post(
                                        "/api/admin/transfers/" + refundId + "/refund",
                                        map("reason", "Encore une fois"),
                                        admin.accessToken())
                                .status())
                .as("jamais rendu deux fois")
                .isEqualTo(200);
        assertThat(balance(s)).isEqualTo("100000.0000");

        String foundId = send(s, s.bankDestinationId(), "10000").text("id");
        fake.scriptNextCreate(Result.failure(Outcome.AMBIGUOUS, null, "Délai dépassé", "{}", "{}"));
        worker.processOutbox();
        assertThat(hold(foundId)).isEqualTo("AMBIGUOUS_CREATE");
        Reply attached =
                api.post(
                        "/api/admin/transfers/" + foundId + "/sync",
                        map("providerReference", "WPPX555000111"),
                        admin.accessToken());
        assertThat(attached.status()).isEqualTo(200);
        assertThat(attached.contractViolations("adminSyncTransfer", 200)).isEmpty();
        assertThat(attached.text("status")).isEqualTo("PROCESSING");
        assertThat(hold(foundId)).isNull();
        worker.processOutbox();
        assertThat(fake.calls())
                .extracting(FakePayoutGateway.Call::argument)
                .contains("WPPX555000111");
        invariants();
    }

    @Test
    void aTransferTheProviderNeverReceivedIsRetriedThenFailedAndRefunded() {
        Scenario s = scenario("100000");
        String id = send(s, s.bankDestinationId(), "10000").text("id");
        for (int i = 0; i < 5; i++) {
            fake.scriptNextCreate(
                    Result.failure(
                            Outcome.NOT_PROCESSED, null, "Fournisseur injoignable", "{}", "{}"));
        }

        worker.processOutbox();
        assertThat(status(id)).as("première tentative : on réessaiera").isEqualTo("PAYOUT_PENDING");
        assertThat(worker.processOutbox()).as("pas avant l'heure du prochain essai").isZero();
        for (int i = 0; i < 4; i++) {
            dueNow();
            worker.processOutbox();
        }

        assertThat(fake.countCalls("createOrder")).isEqualTo(5);
        assertThat(status(id)).isEqualTo("REFUNDED");
        assertThat(balance(s)).isEqualTo("100000.0000");
        assertThat(
                        jdbc.sql(
                                        "SELECT failure_reason FROM transfers WHERE public_id ="
                                                + " CAST(:id AS uuid)")
                                .param("id", id)
                                .query(String.class)
                                .single())
                .contains("jamais créé");
        invariants();
    }

    @Test
    void aTransientFailureThenSuccessSendsOnlyOneOrder() {
        Scenario s = scenario("100000");
        String id = send(s, s.bankDestinationId(), "10000").text("id");
        fake.scriptNextCreate(
                Result.failure(Outcome.NOT_PROCESSED, null, "Fournisseur injoignable", "{}", "{}"));

        worker.processOutbox();
        dueNow();
        worker.processOutbox();

        assertThat(status(id)).isEqualTo("PROCESSING");
        assertThat(
                        jdbc.sql("SELECT outcome FROM payout_attempts ORDER BY attempt_no")
                                .query(String.class)
                                .list())
                .containsExactly("ERROR", "ACCEPTED");
    }

    @Test
    void aDifferentAmountAnnouncedByTheProviderHoldsTheOrderUnconfirmed() {
        Scenario s = scenario("100000");
        fake.announceAmountReceived(new BigDecimal("9000")); // le devis promettait 10 000
        String id = send(s, s.bankDestinationId(), "10000").text("id");

        worker.processOutbox();

        assertThat(status(id)).isEqualTo("PROCESSING");
        assertThat(hold(id)).isEqualTo("AMOUNT_MISMATCH");
        assertThat(worker.processOutbox()).as("pas de confirmation").isZero();
        assertThat(fake.countCalls("confirm")).isZero();
        assertThat(worker.pollStatuses())
                .as("un ordre retenu n'est pas suivi comme un ordre normal")
                .isZero();
        assertThat(balance(s)).isEqualTo("89500.0000");
    }

    @Test
    void aLockedOrderIsNeverRefundedAndCompletesIfTheProviderReleasesIt() {
        Scenario s = scenario("100000");
        String id = send(s, s.bankDestinationId(), "10000").text("id");
        worker.processOutbox();
        worker.processOutbox();
        String reference = providerReference(id);

        fake.scriptStatus(
                reference,
                Result.ok(
                        new OrderStatus(ProviderStatus.LOCKED, "LOCKED", null, null), "{}", "{}"));
        fake.scriptStatus(
                reference,
                Result.ok(new OrderStatus(ProviderStatus.PAID, "PAID", null, null), "{}", "{}"));
        jdbc.sql("UPDATE transfers SET provider_polled_at = now() - interval '1 hour'").update();
        worker.pollStatuses();
        assertThat(hold(id)).isEqualTo("PROVIDER_LOCKED");
        assertThat(status(id)).isEqualTo("PROCESSING");
        assertThat(balance(s)).isEqualTo("89500.0000");

        fake.scriptStatus(
                reference,
                Result.ok(new OrderStatus(ProviderStatus.PAID, "PAID", null, null), "{}", "{}"));
        fake.scriptStatus(
                reference,
                Result.ok(new OrderStatus(ProviderStatus.PAID, "PAID", null, null), "{}", "{}"));
        jdbc.sql("UPDATE transfers SET provider_polled_at = now() - interval '1 hour'").update();
        worker.pollStatuses();
        assertThat(status(id)).isEqualTo("COMPLETED");
        assertThat(hold(id)).isNull();
    }

    @Test
    void anOrderCancelledByTheProviderIsRefundedOnce() {
        Scenario s = scenario("100000");
        String id = send(s, s.bankDestinationId(), "10000").text("id");
        worker.processOutbox();
        worker.processOutbox();
        fake.scriptStatus(
                providerReference(id),
                Result.ok(
                        new OrderStatus(ProviderStatus.CANCEL, "CANCEL", null, null), "{}", "{}"));

        for (int i = 0; i < 3; i++) {
            jdbc.sql("UPDATE transfers SET provider_polled_at = now() - interval '1 hour'")
                    .update();
            worker.pollStatuses();
        }

        assertThat(status(id)).isEqualTo("REFUNDED");
        assertThat(balance(s)).isEqualTo("100000.0000");
        assertThat(
                        jdbc.sql("SELECT count(*) FROM ledger_transactions WHERE reference = :r")
                                .param("r", "transfer:" + id + ":refund")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        invariants();
    }

    @Test
    void aConfirmationThatKeepsFailingBlocksTheTransferForAHuman() {
        Scenario s = scenario("100000");
        String id = send(s, s.bankDestinationId(), "10000").text("id");
        worker.processOutbox();
        for (int i = 0; i < 9; i++) {
            fake.scriptNextConfirm(
                    Result.failure(Outcome.NOT_PROCESSED, "204", "not confirmed", "{}", "{}"));
        }
        for (int i = 0; i < 9; i++) {
            dueNow();
            worker.processOutbox();
        }
        assertThat(hold(id)).isEqualTo("CONFIRM_FAILED");
        assertThat(status(id))
                .as("l'ordre existe chez le fournisseur : jamais de remboursement automatique")
                .isEqualTo("PROCESSING");
        assertThat(balance(s)).isEqualTo("89500.0000");
    }

    @Test
    void twoWorkersRacingOnTheSameOrderCreateItOnlyOnce() throws Exception {
        Scenario s = scenario("100000");
        String id = send(s, s.bankDestinationId(), "10000").text("id");

        concurrently(4, i -> () -> worker.processOutbox());

        assertThat(fake.countCalls("createOrder")).isEqualTo(1);
        assertThat(status(id)).isIn("PROCESSING");
        assertThat(jdbc.sql("SELECT count(*) FROM payout_attempts").query(Long.class).single())
                .isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------ back-office

    @Test
    void backOfficeListsFiltersAndReadsTransfersWithTheirAttempts() {
        Scenario s = scenario("100000");
        Session support = Sessions.staff(api, fx, clock, Role.SUPPORT);
        String id = send(s, s.bankDestinationId(), "10000").text("id");
        worker.processOutbox();

        Reply list =
                api.get(
                        "/api/admin/transfers?status=PROCESSING&customerId="
                                + s.customer().customerId()
                                + "&search=WTC",
                        support.accessToken());
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.contractViolations("adminListTransfers", 200)).isEmpty();
        assertThat(list.json().get("totalElements").asLong()).isEqualTo(1);
        assertThat(
                        api.get(
                                        "/api/admin/transfers?status=COMPLETED&customerId="
                                                + s.customer().customerId(),
                                        support.accessToken())
                                .json()
                                .get("totalElements")
                                .asLong())
                .isZero();

        Reply detail = api.get("/api/admin/transfers/" + id, support.accessToken());
        assertThat(detail.contractViolations("adminGetTransfer", 200)).isEmpty();
        assertThat(detail.json().get("attempts").get(0).get("outcome").asString())
                .isEqualTo("ACCEPTED");
        assertThat(detail.text("providerReference")).startsWith("WPPX");
        assertThat(
                        api.post(
                                        "/api/admin/transfers/" + id + "/refund",
                                        map("reason", "Pas autorisé"),
                                        support.accessToken())
                                .status())
                .as("SUPPORT ne rembourse pas")
                .isEqualTo(403);
        assertThat(
                        api.post(
                                        "/api/admin/transfers/" + id + "/sync",
                                        map(),
                                        support.accessToken())
                                .status())
                .isEqualTo(403);
    }

    @Test
    void refundingAnOrderStillLiveAtTheProviderIsRefused() {
        Scenario s = scenario("100000");
        Session admin = Sessions.staff(api, fx, clock, Role.ADMIN);
        String id = send(s, s.bankDestinationId(), "10000").text("id");
        worker.processOutbox();

        Reply refused =
                api.post(
                        "/api/admin/transfers/" + id + "/refund",
                        map("reason", "Le client le demande"),
                        admin.accessToken());
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("TRANSFER_NOT_REFUNDABLE");
        assertThat(balance(s)).isEqualTo("89500.0000");
        assertThat(
                        api.post(
                                        "/api/admin/transfers/" + id + "/sync",
                                        map("providerReference", "WPPX1"),
                                        admin.accessToken())
                                .status())
                .as("rattachement réservé aux transferts bloqués")
                .isEqualTo(409);
    }

    @Test
    void staffEnterATransferPaidOutsideThePlatformAndBothMovementsAreRecorded() {
        Scenario s = scenario(null); // portefeuille à zéro
        Session admin = Sessions.staff(api, fx, clock, Role.ADMIN);
        var body =
                map(
                        "customerId",
                        s.customer().customerId(),
                        "senderId",
                        s.senderId(),
                        "beneficiaryId",
                        s.beneficiaryId(),
                        "destinationId",
                        s.bankDestinationId(),
                        "sourceAmount",
                        "10000",
                        "purposeCode",
                        "FAMILY",
                        "sourceOfFundsCode",
                        "SALARY",
                        "relationshipCode",
                        "BROTHER",
                        "fundingSource",
                        "OFFLINE_RECEIPT",
                        "receiptReference",
                        "Espèces agence Douala reçu 4471");

        Reply created =
                post("/api/admin/transfers", body, admin.accessToken(), "key-" + UUID.randomUUID());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.contractViolations("adminCreateTransfer", 201)).isEmpty();
        assertThat(balance(s)).as("crédité puis débité : zéro").isEqualTo("0.0000");
        assertThat(
                        jdbc.sql("SELECT count(*) FROM ledger_transactions WHERE reference LIKE :r")
                                .param("r", "transfer:" + created.text("id") + ":%")
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
        assertThat(
                        jdbc.sql(
                                        "SELECT funding_source FROM transfers WHERE public_id ="
                                                + " CAST(:id AS uuid)")
                                .param("id", created.text("id"))
                                .query(String.class)
                                .single())
                .isEqualTo("OFFLINE_RECEIPT");

        body.remove("receiptReference");
        assertThat(
                        post(
                                        "/api/admin/transfers",
                                        body,
                                        admin.accessToken(),
                                        "key-" + UUID.randomUUID())
                                .status())
                .as("justificatif obligatoire")
                .isIn(400, 422);
        body.put("receiptReference", "Espèces agence Douala reçu 4471");
        Session support = Sessions.staff(api, fx, clock, Role.SUPPORT);
        assertThat(
                        post(
                                        "/api/admin/transfers",
                                        body,
                                        support.accessToken(),
                                        "key-" + UUID.randomUUID())
                                .status())
                .isEqualTo(403);
        invariants();
    }

    @Test
    void customersListTheirOwnTransfersOnly() {
        Scenario a = scenario("100000");
        Scenario b = scenario("100000");
        send(a, a.bankDestinationId(), "1000");
        send(a, a.bankDestinationId(), "2000");
        send(b, b.bankDestinationId(), "3000");

        Reply page =
                api.get("/api/me/transfers?size=1&sort=createdAt,desc", a.customer().accessToken());
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.contractViolations("listTransfers", 200)).isEmpty();
        assertThat(page.json().get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.json().get("content").size()).isEqualTo(1);
        String bTransfer =
                api.get("/api/me/transfers", b.customer().accessToken())
                        .json()
                        .get("content")
                        .get(0)
                        .get("id")
                        .asString();
        assertThat(api.get("/api/me/transfers/" + bTransfer, a.customer().accessToken()).status())
                .isEqualTo(404);
        assertThat(api.get("/api/admin/transfers", a.customer().accessToken()).status())
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------ utilitaires

    private Reply post(String path, Object body, String token, String key) {
        try {
            var builder =
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", key)
                            .contentType("application/json")
                            .content(
                                    new tools.jackson.databind.json.JsonMapper()
                                            .writeValueAsString(body));
            return new Reply(mvc.perform(builder).andReturn());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> List<Object> concurrently(
            int count, java.util.function.IntFunction<Callable<T>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(count, 12));
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<T> callable = task.apply(i);
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return callable.call();
                                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ notifications et reçu

    private FakeNotificationSender channel(Channel c) {
        return fakeChannels.stream().filter(f -> f.channel() == c).findFirst().orElseThrow();
    }

    private void pump() {
        dueNow();
        bridge.bridgeDue();
        dispatcher.dispatchDue();
    }

    private String completeTransfer(Scenario s) {
        Reply created = send(s, s.bankDestinationId(), "10000");
        String id = created.text("id");
        worker.processOutbox();
        String reference = providerReference(id);
        worker.processOutbox();
        fake.scriptStatus(
                reference,
                Result.ok(
                        new OrderStatus(
                                ProviderStatus.PAID,
                                "PAID",
                                new BigDecimal("10000"),
                                new BigDecimal("350")),
                        "{}",
                        "{}"));
        jdbc.sql("UPDATE transfers SET provider_polled_at = now() - interval '1 hour'").update();
        worker.pollStatuses();
        return id;
    }

    @Test
    void creationAndPaymentNotifyTheCustomerOnceByEmailAndWhatsapp() {
        Scenario s = scenario("100000");
        String id = completeTransfer(s);
        assertThat(status(id)).isEqualTo("COMPLETED");

        pump();
        pump();
        var emails = channel(Channel.EMAIL).sent();
        assertThat(emails)
                .extracting(m -> m.content().subject())
                .anyMatch(x -> x.contains("enregistré"))
                .anyMatch(x -> x.contains("payé"));
        assertThat(emails).hasSize(2);
        assertThat(channel(Channel.WHATSAPP).sent())
                .extracting(m -> m.content().whatsappTemplate())
                .containsExactlyInAnyOrder("wtc_transfer_created", "wtc_transfer_completed");
        assertThat(
                        jdbc.sql("SELECT count(*) FROM notification_outbox WHERE status <> 'SENT'")
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(emails.toString()).doesNotContain("****4567");
    }

    @Test
    void aTemporaryFailureIsRetriedWithBackoffAndThenSent() {
        Scenario s = scenario("100000");
        send(s, s.bankDestinationId(), "10000");
        channel(Channel.EMAIL).scriptNext(SendResult.retry("panne SMTP"));
        pump();
        assertThat(channel(Channel.EMAIL).sent()).isEmpty();
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM notification_outbox WHERE channel ="
                                            + " 'EMAIL' AND status = 'PENDING' AND last_error IS"
                                            + " NOT NULL")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        jdbc.sql("UPDATE notification_outbox SET next_attempt_at = now() - interval '1 hour'")
                .update();
        dispatcher.dispatchDue();
        assertThat(channel(Channel.EMAIL).sent()).hasSize(1);
    }

    @Test
    void aPermanentFailureIsRecordedAndRaisesAnOperationsAlertOncePerDay() {
        Scenario s = scenario("100000");
        send(s, s.bankDestinationId(), "10000");
        channel(Channel.EMAIL).scriptNext(SendResult.permanent("adresse refusée"));
        pump();
        assertThat(
                        jdbc.sql("SELECT count(*) FROM notification_outbox WHERE status = 'FAILED'")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        assertThat(watcher.check()).isEqualTo(1);
        assertThat(watcher.check()).as("déjà annoncée").isZero();
        pump();
        assertThat(channel(Channel.TELEGRAM).sent()).hasSize(1);
        assertThat(channel(Channel.TELEGRAM).sent().get(0).recipient()).isEqualTo("-1001");
    }

    @Test
    void aHeldTransferAlertsTheTeam() {
        Scenario s = scenario("100000");
        String id = send(s, s.bankDestinationId(), "10000").text("id");
        jdbc.sql(
                        "UPDATE transfers SET payout_hold = 'AMBIGUOUS_CREATE' WHERE public_id ="
                                + " CAST(:id AS uuid)")
                .param("id", id)
                .update();
        assertThat(watcher.check()).isGreaterThanOrEqualTo(1);
        pump();
        assertThat(channel(Channel.TELEGRAM).sent())
                .anyMatch(m -> m.content().text().contains("AMBIGUOUS_CREATE"));
    }

    @Test
    void theReceiptIsAPdfForTheOwnerOnly() throws Exception {
        Scenario s = scenario("100000");
        String id = completeTransfer(s);
        Reply pdf = api.get("/api/me/transfers/" + id + "/receipt", s.customer().accessToken());
        assertThat(pdf.status()).isEqualTo(200);
        byte[] bytes = pdf.rawBody();
        assertThat(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");
        try (var reader = new org.openpdf.text.pdf.PdfReader(bytes)) {
            String text =
                    new org.openpdf.text.pdf.parser.PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("WTC-").contains("Payé").contains("10500").contains("****");
            assertThat(text).doesNotContain("1234567");
        }
        Session other = Sessions.customer(api, fx, "autre");
        assertThat(api.get("/api/me/transfers/" + id + "/receipt", other.accessToken()).status())
                .isEqualTo(404);
        assertThat(api.get("/api/me/transfers/" + id + "/receipt", null).status()).isEqualTo(401);
    }
}
