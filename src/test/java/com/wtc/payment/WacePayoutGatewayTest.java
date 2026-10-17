package com.wtc.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wtc.payment.application.PayoutGateway.BeneficiaryData;
import com.wtc.payment.application.PayoutGateway.OrderCreated;
import com.wtc.payment.application.PayoutGateway.Outcome;
import com.wtc.payment.application.PayoutGateway.PayoutOrder;
import com.wtc.payment.application.PayoutGateway.ProviderStatus;
import com.wtc.payment.application.PayoutGateway.Result;
import com.wtc.payment.application.PayoutGateway.SenderData;
import com.wtc.payment.infrastructure.WacePayoutGateway;
import com.wtc.shared.config.WtcProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L'adaptateur Wace face à un faux serveur qui rejoue les exemples de la spécification v2.0.0
 * (statuts, codes d'erreur, orthographes incohérentes comme « stauts »). Ne remplace pas un essai
 * sur la recette de Wace.
 */
class WacePayoutGatewayTest {

    record Seen(String method, String path, String authorization, String body) {}

    private HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<Seen> seen = new ArrayList<>();
    private final AtomicInteger logins = new AtomicInteger();
    private volatile boolean rejectNextToken;
    private WacePayoutGateway gateway;

    record Reply(int http, String body, long delayMillis) {
        static Reply ok(String body) {
            return new Reply(200, body, 0);
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        gateway = gatewayFor(server.getAddress().getPort(), Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private WacePayoutGateway gatewayFor(int port, Duration timeout) {
        return new WacePayoutGateway(
                new WtcProperties.Wace(
                        "http://127.0.0.1:" + port,
                        "partner@example.test",
                        "s3cret-password",
                        Duration.ofSeconds(1),
                        timeout),
                JsonMapper.builder().build(),
                Clock.systemUTC());
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        synchronized (seen) {
            seen.add(
                    new Seen(
                            exchange.getRequestMethod(),
                            path,
                            exchange.getRequestHeaders().getFirst("Authorization"),
                            body));
        }
        Reply reply;
        if (path.equals("/login")) {
            logins.incrementAndGet();
            reply =
                    Reply.ok(
                            "{\"status\":2000,\"message\":\"Token created"
                                    + " successfully\",\"access_token\":\"tok-"
                                    + logins.get()
                                    + "\",\"token_type\":\"bearer\",\"expires_in\":3600}");
        } else if (rejectNextToken) {
            rejectNextToken = false;
            reply = new Reply(401, "{\"status\":1002,\"message\":\"Unauthorized\"}", 0);
        } else {
            reply =
                    replies.getOrDefault(
                            path,
                            new Reply(
                                    404,
                                    "{\"status\":1013,\"message\":\"reference not found\"}",
                                    0));
        }
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.http(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private PayoutOrder walletOrder() {
        return new PayoutOrder(
                PayoutOrder.Kind.WALLET,
                "16845316212",
                new BigDecimal("1080"),
                "XAF",
                "CM",
                "17500964013",
                "17709171953",
                "Loan",
                "Family Maintainance",
                "Spouse",
                "26654450780",
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private PayoutOrder bankOrder() {
        return new PayoutOrder(
                PayoutOrder.Kind.BANK,
                "1753881196",
                new BigDecimal("300"),
                "XAF",
                "CM",
                "17500964013",
                "17709171953",
                "salary",
                "Family Maintainance",
                "brother",
                null,
                "14785296321",
                "Afriland",
                5L,
                "10005",
                "10005",
                "ROSMFFHDH");
    }

    private Seen lastCallTo(String path) {
        synchronized (seen) {
            return seen.reversed().stream()
                    .filter(s -> s.path().equals(path))
                    .findFirst()
                    .orElseThrow();
        }
    }

    // ------------------------------------------------------------------ authentification

    @Test
    void logsInOnceAndReusesTheTokenForLaterCalls() {
        replies.put(
                "/account/balance",
                Reply.ok(
                        "{\"status\":2000,\"data\":{\"Agency\":\"DigitWace"
                                + " SARL\",\"Balance\":\"7964365.000\",\"Currency\":\"XAF\"}}"));

        Result<?> first = gateway.balance();
        gateway.balance();
        gateway.balance();

        assertThat(first.isOk()).isTrue();
        assertThat(logins.get()).as("un seul login pour trois appels").isEqualTo(1);
        assertThat(lastCallTo("/account/balance").authorization()).isEqualTo("Bearer tok-1");
        assertThat(lastCallTo("/login").body()).contains("partner@example.test");
    }

    @Test
    void parsesThePartnerBalance() {
        replies.put(
                "/account/balance",
                Reply.ok(
                        "{\"status\":2000,\"data\":{\"Agency\":\"DigitWace"
                                + " SARL\",\"Balance\":\"7964365.000\",\"Currency\":\"XAF\"}}"));
        var balance = gateway.balance().value();
        assertThat(balance.balance()).isEqualByComparingTo("7964365");
        assertThat(balance.currency()).isEqualTo("XAF");
    }

    @Test
    void anExpiredTokenIsRenewedOnceAndTheCallIsRepeated() {
        replies.put(
                "/account/balance",
                Reply.ok("{\"status\":2000,\"data\":{\"Balance\":\"10\",\"Currency\":\"XAF\"}}"));
        gateway.balance();
        rejectNextToken = true;

        Result<?> result = gateway.balance();

        assertThat(result.isOk()).isTrue();
        assertThat(logins.get()).as("reconnexion après le 401").isEqualTo(2);
        assertThat(lastCallTo("/account/balance").authorization()).isEqualTo("Bearer tok-2");
    }

    @Test
    void refusedCredentialsMeanNothingWasSent() {
        server.removeContext("/");
        server.createContext(
                "/",
                ex -> {
                    byte[] body =
                            "{\"status\":1002,\"message\":\"Unauthorized\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(401, body.length);
                    ex.getResponseBody().write(body);
                    ex.close();
                });
        Result<OrderCreated> result = gateway.createOrder(walletOrder());
        assertThat(result.outcome())
                .as("ordre jamais envoyé : on peut recommencer")
                .isEqualTo(Outcome.NOT_PROCESSED);
    }

    // ------------------------------------------------------------------ expéditeur, bénéficiaire,
    // ordres

    @Test
    void createsASenderAndReadsItsCodeEvenWithTheSpecsMisspelledStatusField() {
        replies.put(
                "/sender/create",
                Reply.ok(
                        "{\"messages\":\"Sender save"
                            + " successfully\",\"sender\":{\"Code\":\"000007\",\"FirstName\":\"Django\"},\"stauts\":2000}"));
        var data =
                new SenderData(
                        "P",
                        "Claire",
                        "Dupont",
                        null,
                        null,
                        "Douala",
                        "+23767418529",
                        "claire@example.test",
                        "CM",
                        "Douala",
                        "F",
                        "Single",
                        "147852963",
                        "PP",
                        "Soudeur",
                        "CM",
                        LocalDate.of(1990, 3, 3),
                        null,
                        LocalDate.of(2030, 5, 5),
                        false);

        Result<String> result = gateway.createSender(data);

        assertThat(result.isOk()).isTrue();
        assertThat(result.value()).isEqualTo("000007");
        String sent = lastCallTo("/sender/create").body();
        assertThat(sent)
                .contains(
                        "\"type\":\"P\"",
                        "\"pep\":0",
                        "\"dateExpireId\":\"2030-05-05\"",
                        "\"dateOfBirth\":\"1990-03-03\"");
        assertThat(result.requestAudit())
                .as("pièce et téléphone masqués dans la trace")
                .doesNotContain("147852963")
                .doesNotContain("23767418529")
                .contains("****");
    }

    @Test
    void createsABeneficiaryLinkedToItsSender() {
        replies.put(
                "/beneficiary/create",
                Reply.ok(
                        "{\"messages\":\"Beneficiary save"
                            + " successfully\",\"beneficiary\":{\"Code\":\"17500969353\"},\"status\":201}"));
        var data =
                new BeneficiaryData(
                        "B",
                        null,
                        null,
                        "WISE BELGIQUE",
                        "SAS",
                        "BRUXELLES",
                        "+33674185296",
                        null,
                        "BE",
                        "BRUXELLES",
                        "147852963",
                        "RCCM",
                        null,
                        null);

        Result<String> result = gateway.createBeneficiary("17500968683", data);

        assertThat(result.value()).isEqualTo("17500969353");
        assertThat(lastCallTo("/beneficiary/create").body())
                .contains("\"sender_code\":\"17500968683\"", "\"idType\":\"RCCM\"");
    }

    @Test
    void createsAWalletOrderWithTheSpecFieldsAndReadsTheAnnouncedAmounts() {
        replies.put(
                "/transaction/wallet/create",
                Reply.ok(
                        "{\"message\":\"Transaction successfully"
                            + " created\",\"transaction\":{\"reference\":\"WPPX1478234857\",\"service\":\"ORANGE"
                            + " MONEY\",\"amount_receive\":1080,\"feeds\":350,\"amount_to_send\":1080,\"total_amount_paid\":1430},\"status\":201}"));

        Result<OrderCreated> result = gateway.createOrder(walletOrder());

        assertThat(result.isOk()).isTrue();
        assertThat(result.value().reference()).isEqualTo("WPPX1478234857");
        assertThat(result.value().amountReceived()).isEqualByComparingTo("1080");
        assertThat(result.value().providerFee()).isEqualByComparingTo("350");
        assertThat(result.value().totalCharged()).isEqualByComparingTo("1430");
        assertThat(lastCallTo("/transaction/wallet/create").body())
                .contains(
                        "\"payerCode\":\"16845316212\"",
                        "\"amountToPaid\":\"1080\"",
                        "\"mobileReceiveNumber\":\"26654450780\"",
                        "\"originFund\":\"Loan\"",
                        "\"relation\":\"Spouse\"");
        assertThat(result.requestAudit()).doesNotContain("26654450780");
    }

    @Test
    void createsABankOrderAndKeepsTheReferenceCommentForTracing() {
        replies.put(
                "/transaction/bank/create",
                Reply.ok(
                        "{\"message\":\"Transaction created"
                            + " successfully\",\"transaction\":{\"reference\":\"WPPX919049923\",\"reference_transaction\":\"ROSMFFHDH\","
                            + "\"amount_receive\":300,\"feeds\":7.5,\"amount_paid\":300,\"total_amount_paid\":307.5},\"status\":201}"));

        Result<OrderCreated> result = gateway.createOrder(bankOrder());

        assertThat(result.value().providerFee()).isEqualByComparingTo("7.5");
        assertThat(lastCallTo("/transaction/bank/create").body())
                .contains(
                        "\"bankId\":5",
                        "\"bankSwCode\":\"10005\"",
                        "\"comment\":\"ROSMFFHDH\"",
                        "\"bankAccount\":\"14785296321\"");
        assertThat(result.requestAudit()).doesNotContain("14785296321");
    }

    // ------------------------------------------------------------------ confirmation et statut

    @Test
    void confirmingIsASuccessAndConfirmingTwiceIsToo() {
        replies.put(
                "/transaction/confirm",
                Reply.ok(
                        "{\"status\":2000,\"messages\":\"Data with reference WPCED2735997245 is"
                                + " confirm successfully\"}"));
        assertThat(gateway.confirm("WPCED2735997245").isOk()).isTrue();

        replies.put(
                "/transaction/confirm",
                Reply.ok(
                        "{\"status\":1016,\"message\":\"This transaction has already been"
                                + " successfully confirmed.\"}"));
        assertThat(gateway.confirm("WPCED2735997245").isOk()).as("déjà confirmé = succès").isTrue();
    }

    @Test
    void aConfirmationTheProviderHasNotTakenIntoAccountMayBeRetried() {
        replies.put(
                "/transaction/confirm",
                Reply.ok("{\"status\":204,\"message\":\"transaction not confirm by Partner\"}"));
        assertThat(gateway.confirm("WPCED1").outcome()).isEqualTo(Outcome.NOT_PROCESSED);
    }

    @Test
    void readsTheStatusAndTheMessyAmountFormats() {
        replies.put(
                "/transaction/status/WPPX1478234857",
                Reply.ok(
                        "{\"status\":204,\"messages\":\"Data with reference WPPX1478234857 returned"
                            + " successfully\",\"transaction\":{\"Agency\":\"DIGITWACE"
                            + " SARL\",\"AmountSend\":\"1080.000XAF\",\"AmountToReceive\":\"1080.000XAF\",\"fees\":\"350.000\",\"Status\":\"WAITING"
                            + " CONFIRMATION\"}}"));

        var status = gateway.status("WPPX1478234857").value();

        assertThat(status.status()).isEqualTo(ProviderStatus.WAITING_CONFIRMATION);
        assertThat(status.amountToReceive()).isEqualByComparingTo("1080");
        assertThat(status.providerFee()).isEqualByComparingTo("350");
    }

    @Test
    void mapsEveryDocumentedStatus() {
        Map<String, ProviderStatus> expected =
                Map.of(
                        "PENDING",
                        ProviderStatus.PENDING,
                        "WAITING CONFIRMATION",
                        ProviderStatus.WAITING_CONFIRMATION,
                        "PROCESSING",
                        ProviderStatus.PROCESSING,
                        "PAID",
                        ProviderStatus.PAID,
                        "CANCEL",
                        ProviderStatus.CANCEL,
                        "LOCKED",
                        ProviderStatus.LOCKED,
                        "SOMETHING NEW",
                        ProviderStatus.UNKNOWN);
        expected.forEach(
                (raw, mapped) -> {
                    replies.put(
                            "/transaction/status/R1",
                            Reply.ok(
                                    "{\"status\":205,\"transaction\":{\"Status\":\""
                                            + raw
                                            + "\"}}"));
                    assertThat(gateway.status("R1").value().status()).as(raw).isEqualTo(mapped);
                });
    }

    @Test
    void anUnknownReferenceIsARefusalNotAGuess() {
        Result<?> result = gateway.status("NOPE");
        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.code()).isEqualTo("1013");
    }

    // ------------------------------------------------------------------ classement des échecs de
    // création

    private Result<OrderCreated> createWith(Reply reply) {
        replies.put("/transaction/wallet/create", reply);
        return gateway.createOrder(walletOrder());
    }

    @Test
    void documentedValidationAndConfigurationErrorsAreExplicitRefusals() {
        for (String code :
                List.of(
                        "1001", "1003", "1011", "2008", "2010", "2011", "2012", "2013", "2014",
                        "3001", "6001")) {
            Result<OrderCreated> result =
                    createWith(
                            Reply.ok(
                                    "{\"status\":"
                                            + code
                                            + ",\"message\":\"No feeds is configure to this"
                                            + " amount\"}"));
            assertThat(result.outcome()).as(code).isEqualTo(Outcome.REJECTED);
            assertThat(result.code()).isEqualTo(code);
            assertThat(result.message()).isNotBlank();
        }
    }

    @Test
    void systemErrorsAndEverythingUnclearAreAmbiguousAndNeverRefusals() {
        assertThat(
                        createWith(
                                        Reply.ok(
                                                "{\"status\":1005,\"message\":\"System error."
                                                        + " Please try again.\"}"))
                                .outcome())
                .isEqualTo(Outcome.AMBIGUOUS);
        assertThat(createWith(Reply.ok("{\"status\":9999,\"message\":\"?\"}")).outcome())
                .as("code inconnu")
                .isEqualTo(Outcome.AMBIGUOUS);
        assertThat(createWith(new Reply(500, "{\"status\":500}", 0)).outcome())
                .isEqualTo(Outcome.AMBIGUOUS);
        assertThat(createWith(new Reply(503, "", 0)).outcome()).isEqualTo(Outcome.AMBIGUOUS);
        assertThat(createWith(Reply.ok("<html>Bad gateway</html>")).outcome())
                .as("réponse illisible")
                .isEqualTo(Outcome.AMBIGUOUS);
        assertThat(createWith(Reply.ok("{\"message\":\"pas de statut\"}")).outcome())
                .isEqualTo(Outcome.AMBIGUOUS);
        assertThat(createWith(Reply.ok("{\"status\":201,\"transaction\":{}}")).outcome())
                .as("succès sans référence : on ne sait pas")
                .isEqualTo(Outcome.AMBIGUOUS);
    }

    @Test
    void aTimeoutIsAmbiguousBecauseTheOrderMayHaveBeenCreated() {
        gateway = gatewayFor(server.getAddress().getPort(), Duration.ofMillis(300));
        Result<OrderCreated> result = createWith(new Reply(200, "{\"status\":201}", 1500));
        assertThat(result.outcome()).isEqualTo(Outcome.AMBIGUOUS);
    }

    @Test
    void anUnreachableServerIsNotProcessedBecauseNothingLeftTheBuilding() {
        int port = server.getAddress().getPort();
        server.stop(0);
        WacePayoutGateway offline = gatewayFor(port, Duration.ofSeconds(1));
        assertThat(offline.createOrder(walletOrder()).outcome()).isEqualTo(Outcome.NOT_PROCESSED);
    }

    @Test
    void rateLimitingMeansTheOrderWasNotTaken() {
        assertThat(createWith(new Reply(429, "{\"status\":429}", 0)).outcome())
                .isEqualTo(Outcome.NOT_PROCESSED);
    }

    @Test
    void secretsNeverAppearInAuditTraces() {
        replies.put(
                "/transaction/wallet/create",
                Reply.ok(
                        "{\"status\":201,\"transaction\":{\"reference\":\"R9\",\"wallet_payment\":\"26654450780\",\"amount_receive\":1080}}"));
        Result<OrderCreated> result = gateway.createOrder(walletOrder());
        assertThat(result.responseAudit()).doesNotContain("26654450780");
        assertThat(result.requestAudit() + result.responseAudit())
                .doesNotContain("s3cret-password")
                .doesNotContain("tok-1");
    }

    // ------------------------------------------------------------------ référentiels (section VII
    // et suivantes)

    @Test
    void readsThePayerCodeOfAServiceInACountry() {
        replies.put(
                "/transaction/payercode",
                Reply.ok("{\"status\":2000,\"transaction\":{\"PayerCode\":\"1684531621\"}}"));

        Result<String> result = gateway.payerCode("CM", "MTN", "XAF");

        assertThat(result.value()).isEqualTo("1684531621");
        assertThat(lastCallTo("/transaction/payercode").body())
                .contains(
                        "\"toCountry\":\"CM\"",
                        "\"payoutService\":\"MTN\"",
                        "\"toCurrency\":\"XAF\"");
    }

    @Test
    void listsTheServicesOfACountry() {
        replies.put(
                "/transaction/payouts/services",
                Reply.ok(
                        "{\"status\":2000,\"data\":[{\"CurrencyName\":\"CFA"
                            + " Franc\",\"CurrencyCode\":\"XOF\",\"CountryIso3\":\"CIV\",\"CountryPrefix\":\"225\",\"CountryName\":\"COTE"
                            + " D IVOIRE\",\"ServiceCode\":\"B\",\"ServiceName\":\"BANKS"
                            + " DEPOSIT\"},"
                            + "{\"CurrencyCode\":\"XOF\",\"ServiceCode\":\"WV\",\"ServiceName\":\"WAVE\"}]}"));

        var services = gateway.payoutServices("CI", "XOF").value();

        assertThat(services).extracting("code").containsExactly("B", "WV");
        assertThat(services.get(0).name()).isEqualTo("BANKS DEPOSIT");
        assertThat(lastCallTo("/transaction/payouts/services").body())
                .contains("\"payoutCountry\":\"CI\"", "\"payoutCurrency\":\"XOF\"");
    }

    @Test
    void listsTheBanksOfACountry() {
        replies.put(
                "/transaction/bank/list",
                Reply.ok(
                        "{\"status\":2000,\"data\":[{\"BankName\":\"BICEC\",\"BankID\":1,\"BankCode\":\"10001\"},"
                            + "{\"BankName\":\"Afriland\",\"BankID\":5,\"BankCode\":\"10005\"}]}"));

        var banks = gateway.banks("CM", "1753881196").value();

        assertThat(banks).extracting("name").containsExactly("BICEC", "Afriland");
        assertThat(banks.get(1).id()).isEqualTo(5L);
        assertThat(banks.get(1).code()).isEqualTo("10005");
        assertThat(lastCallTo("/transaction/bank/list").body())
                .contains("\"iso2\":\"CM\"", "\"payercode\":\"1753881196\"");
    }

    @Test
    void readsTheLabelListsThatCarryNoStatusField() {
        replies.put(
                "/transaction/relation",
                Reply.ok("{\"Brother\":\"Brother\",\"Sister In Law\":\"Sister In Law\"}"));
        replies.put(
                "/transaction/origin_fund/p2p",
                Reply.ok("{\"Salary\":\"Salary\",\"Business Income\":\"Business Income\"}"));
        replies.put(
                "/transaction/reason/b2b", Reply.ok("{\"Software Export\":\"Software Export\"}"));

        assertThat(gateway.relations().value())
                .containsEntry("Sister In Law", "Sister In Law")
                .hasSize(2);
        assertThat(gateway.originFunds("P2P").value()).containsKey("Business Income");
        assertThat(gateway.reasons("B2B").value()).containsOnlyKeys("Software Export");
        assertThat(lastCallTo("/transaction/origin_fund/p2p").method()).isEqualTo("GET");
    }

    @Test
    void aLabelListThatIsActuallyAnErrorIsNotMistakenForData() {
        replies.put(
                "/transaction/relation",
                Reply.ok("{\"status\":1002,\"message\":\"Unauthorized\"}"));
        assertThat(gateway.relations().isOk()).isFalse();
        replies.put("/transaction/origin_fund/p2p", Reply.ok("[]"));
        assertThat(gateway.originFunds("P2P").outcome())
                .as("liste vide ou illisible : on ne devine pas")
                .isEqualTo(Outcome.AMBIGUOUS);
    }
}
