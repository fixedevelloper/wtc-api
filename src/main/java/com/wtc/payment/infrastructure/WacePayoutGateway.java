package com.wtc.payment.infrastructure;

import com.wtc.payment.application.PayoutGateway;
import com.wtc.shared.config.WtcProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adaptateur de l'API Wace Pay (spécification v2.0.0 du 10/02/2025).
 *
 * <p><b>Principe de prudence</b> : tout ce qui est incertain est classé {@code AMBIGUOUS}. Seuls
 * sont classés {@code REJECTED} les codes d'erreur que la spécification définit comme des refus de
 * validation ou de configuration, et {@code NOT_PROCESSED} les échecs qui surviennent <i>avant</i>
 * que l'ordre n'atteigne Wace (connexion impossible, authentification refusée, limite de débit).
 * Cette classe n'a jamais pu être validée contre le vrai service (pas d'accès à la recette) : elle
 * l'est contre un faux serveur qui rejoue les exemples de la spécification.
 */
public final class WacePayoutGateway implements PayoutGateway {

    /**
     * Codes d'erreur Wace qui signifient « refusé, rien n'a été créé » (spécification, section
     * III).
     */
    private static final Set<String> REJECTION_CODES =
            Set.of(
                    "1001", "1003", "1004", "1007", "1008", "1009", "1011", "1012", "1013", "1014",
                    "1015", "1017", "2001", "2002", "2003", "2004", "2006", "2007", "2008", "2010",
                    "2011", "2012", "2013", "2014", "2015", "3001", "3002", "3003", "6001", "6002",
                    "6003");

    private static final Set<String> SUCCESS_CODES = Set.of("2000", "200", "201");
    private static final Set<String> MASKED_KEYS =
            Set.of(
                    "password",
                    "access_token",
                    "idnumber",
                    "bankaccount",
                    "mobilereceivenumber",
                    "mobiletopup",
                    "mobile",
                    "phone",
                    "phonenumber",
                    "wallet_payment",
                    "bank_account",
                    "email",
                    "senderidnumber",
                    "idndumber",
                    "response",
                    "question");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final Duration TOKEN_MARGIN = Duration.ofSeconds(60);

    private record Token(String value, Instant expiresAt) {}

    private final WtcProperties.Wace config;
    private final JsonMapper json;
    private final Clock clock;
    private final HttpClient http;
    private final Object loginLock = new Object();
    private volatile Token token;

    public WacePayoutGateway(WtcProperties.Wace config, JsonMapper json, Clock clock) {
        if (config.baseUrl() == null
                || config.baseUrl().isBlank()
                || config.email() == null
                || config.email().isBlank()
                || config.password() == null
                || config.password().isBlank()) {
            throw new IllegalStateException(
                    "wtc.payment.wace.base-url, email et password sont obligatoires avec le"
                            + " fournisseur « wace ».");
        }
        this.config = config;
        this.json = json;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
    }

    // ------------------------------------------------------------------ opérations

    @Override
    public Result<String> createSender(SenderData d) {
        ObjectNode body = json.createObjectNode();
        body.put("type", d.type());
        putIf(body, "firstName", d.firstName());
        putIf(body, "lastName", d.lastName());
        putIf(body, "businessName", d.businessName());
        putIf(body, "businessType", d.businessType());
        body.put("address", nz(d.address()));
        body.put("phone", nz(d.phone()));
        putIf(body, "email", d.email());
        body.put("country", d.country());
        body.put("city", nz(d.city()));
        body.put("gender", nz(d.gender()));
        body.put("civility", nz(d.civility()));
        body.put("idNumber", nz(d.idNumber()));
        body.put("idType", nz(d.idType()));
        putIf(body, "occupation", d.occupation());
        body.put("nationality", nz(d.nationality()));
        body.put("dateOfBirth", d.dateOfBirth() == null ? "" : d.dateOfBirth().toString());
        if (d.businessRegisteredOn() != null) {
            body.put(
                    "regiterBusinessDate",
                    d.businessRegisteredOn().toString()); // sic : orthographe de la spécification
        }
        body.put("dateExpireId", d.idExpiresOn() == null ? "" : d.idExpiresOn().toString());
        body.put("pep", d.pep() ? 1 : 0);
        return exchange(
                "POST",
                "/sender/create",
                body,
                node -> text(node.path("sender"), "Code"),
                "code expéditeur");
    }

    @Override
    public Result<String> createBeneficiary(String senderCode, BeneficiaryData d) {
        ObjectNode body = json.createObjectNode();
        body.put("type", d.type());
        putIf(body, "firstName", d.firstName());
        putIf(body, "lastName", d.lastName());
        putIf(body, "businessName", d.businessName());
        putIf(body, "businessType", d.businessType());
        body.put("address", nz(d.address()));
        body.put("phone", nz(d.phone()));
        putIf(body, "mobile", d.phone() == null ? null : d.phone().replace("+", ""));
        body.put("country", d.country());
        body.put("city", nz(d.city()));
        putIf(body, "email", d.email());
        body.put("idNumber", nz(d.idNumber()));
        body.put("idType", nz(d.idType()));
        if (d.dateOfBirth() != null) {
            body.put("dob", d.dateOfBirth().toString());
        }
        if (d.idExpiresOn() != null) {
            body.put("expire_date", d.idExpiresOn().toString());
        }
        body.put("sender_code", senderCode);
        return exchange(
                "POST",
                "/beneficiary/create",
                body,
                node -> text(node.path("beneficiary"), "Code"),
                "code bénéficiaire");
    }

    @Override
    public Result<OrderCreated> createOrder(PayoutOrder o) {
        ObjectNode body = json.createObjectNode();
        body.put("payerCode", o.payerCode());
        body.put("amountToPaid", o.amountToPay().toPlainString());
        body.put("senderCode", o.senderCode());
        body.put("beneficiaryCode", o.beneficiaryCode());
        body.put("fromCurrency", o.fromCurrency());
        body.put("fromCountry", o.fromCountry());
        body.put("originFund", o.originFund());
        body.put("reason", o.reason());
        body.put("relation", o.relation());
        String path;
        if (o.kind() == PayoutOrder.Kind.WALLET) {
            path = "/transaction/wallet/create";
            body.put("mobileReceiveNumber", o.mobileNumber());
        } else {
            path = "/transaction/bank/create";
            body.put("bankAccount", o.bankAccount());
            body.put("bankName", o.bankName());
            if (o.bankId() != null) {
                body.put("bankId", o.bankId());
            }
            body.put("bankBranch", nz(o.bankBranch()));
            body.put("bankSwCode", nz(o.bankSwiftCode()));
            body.put("comment", nz(o.comment()));
        }
        return exchange(
                "POST",
                path,
                body,
                node -> {
                    JsonNode tx = node.path("transaction");
                    String reference = text(tx, "reference");
                    if (reference == null) {
                        return null;
                    }
                    return new OrderCreated(
                            reference,
                            number(tx.get("amount_receive")),
                            number(tx.get("feeds")),
                            number(tx.get("total_amount_paid")));
                },
                "référence d'ordre");
    }

    @Override
    public Result<Boolean> confirm(String reference) {
        ObjectNode body = json.createObjectNode();
        body.put("reference", reference);
        return exchange("POST", "/transaction/confirm", body, node -> Boolean.TRUE, "confirmation");
    }

    @Override
    public Result<OrderStatus> status(String reference) {
        return exchange(
                "GET",
                "/transaction/status/" + reference,
                null,
                node -> {
                    JsonNode tx = node.path("transaction");
                    String raw = text(tx, "Status");
                    if (raw == null) {
                        return null;
                    }
                    return new OrderStatus(
                            parseStatus(raw),
                            raw,
                            number(tx.get("AmountToReceive")),
                            number(tx.get("fees")));
                },
                "statut");
    }

    @Override
    public Result<PartnerBalance> balance() {
        return exchange(
                "GET",
                "/account/balance",
                null,
                node -> {
                    JsonNode data = node.path("data");
                    BigDecimal value = number(data.get("Balance"));
                    return value == null
                            ? null
                            : new PartnerBalance(
                                    text(data, "Agency"), value, text(data, "Currency"));
                },
                "solde");
    }

    // ------------------------------------------------------------------ référentiels (section VII
    // et suivantes)

    @Override
    public Result<List<PayoutService>> payoutServices(String country, String currency) {
        ObjectNode body = json.createObjectNode();
        body.put("payoutCountry", country);
        body.put("payoutCurrency", currency);
        return exchange(
                "POST",
                "/transaction/payouts/services",
                body,
                node -> {
                    JsonNode data = node.get("data");
                    if (data == null || !data.isArray()) {
                        return null;
                    }
                    List<PayoutService> services = new ArrayList<>();
                    data.forEach(
                            item -> {
                                String code = text(item, "ServiceCode");
                                if (code != null) {
                                    services.add(
                                            new PayoutService(
                                                    code,
                                                    text(item, "ServiceName"),
                                                    text(item, "CurrencyCode")));
                                }
                            });
                    return services;
                },
                "liste des services");
    }

    @Override
    public Result<String> payerCode(String country, String service, String currency) {
        ObjectNode body = json.createObjectNode();
        body.put(
                "toCountry",
                country); // les noms de champs sont ceux de l'exemple de la spécification
        body.put("payoutService", service);
        body.put("toCurrency", currency);
        return exchange(
                "POST",
                "/transaction/payercode",
                body,
                node -> text(node.path("transaction"), "PayerCode"),
                "code payeur");
    }

    @Override
    public Result<List<ProviderBank>> banks(String country, String payerCode) {
        ObjectNode body = json.createObjectNode();
        body.put("iso2", country);
        body.put("payercode", payerCode);
        return exchange(
                "POST",
                "/transaction/bank/list",
                body,
                node -> {
                    JsonNode data = node.get("data");
                    if (data == null || !data.isArray()) {
                        return null;
                    }
                    List<ProviderBank> banks = new ArrayList<>();
                    data.forEach(
                            item -> {
                                JsonNode id = item.get("BankID");
                                String name = text(item, "BankName");
                                if (id != null && id.canConvertToLong() && name != null) {
                                    banks.add(
                                            new ProviderBank(
                                                    id.asLong(), text(item, "BankCode"), name));
                                }
                            });
                    return banks;
                },
                "liste des banques");
    }

    @Override
    public Result<Map<String, String>> relations() {
        return exchange(
                "GET",
                "/transaction/relation",
                null,
                WacePayoutGateway::labels,
                "liste des relations",
                true);
    }

    @Override
    public Result<Map<String, String>> originFunds(String businessType) {
        return exchange(
                "GET",
                "/transaction/origin_fund/" + businessType.toLowerCase(Locale.ROOT),
                null,
                WacePayoutGateway::labels,
                "liste des origines des fonds",
                true);
    }

    @Override
    public Result<Map<String, String>> reasons(String businessType) {
        return exchange(
                "GET",
                "/transaction/reason/" + businessType.toLowerCase(Locale.ROOT),
                null,
                WacePayoutGateway::labels,
                "liste des motifs",
                true);
    }

    /**
     * {@code {"Brother": "Brother"}} → carte clé → libellé ; vide si ce n'est pas un objet de
     * textes.
     */
    private static Map<String, String> labels(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        Map<String, String> map = new LinkedHashMap<>();
        node.properties()
                .forEach(
                        e -> {
                            if (e.getValue().isString()
                                    && !e.getKey().equals("status")
                                    && !e.getKey().equals("message")) {
                                map.put(e.getKey(), e.getValue().asString());
                            }
                        });
        return map.isEmpty() ? null : map;
    }

    // ------------------------------------------------------------------ transport et
    // classification

    private <T> Result<T> exchange(
            String method,
            String path,
            ObjectNode body,
            Function<JsonNode, T> parser,
            String expected) {
        return exchange(method, path, body, parser, expected, false);
    }

    /**
     * @param statusOptional les listes de référence (relations, motifs, origines) sont des objets
     *     {@code {clé: libellé}} sans champ de statut : leur absence n'est alors pas une anomalie
     */
    private <T> Result<T> exchange(
            String method,
            String path,
            ObjectNode body,
            Function<JsonNode, T> parser,
            String expected,
            boolean statusOptional) {
        String requestAudit = body == null ? method + " " + path : mask(body);
        Result<Void> auth = ensureToken();
        if (!auth.isOk()) {
            return Result.failure(auth.outcome(), auth.code(), auth.message(), requestAudit, null);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpResponse<String> response;
            try {
                response =
                        http.send(
                                request(method, path, body),
                                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (HttpConnectTimeoutException
                    | ConnectException
                    | UnresolvedAddressException e) {
                return Result.failure(
                        Outcome.NOT_PROCESSED,
                        null,
                        "Fournisseur injoignable : " + e.getClass().getSimpleName(),
                        requestAudit,
                        null);
            } catch (HttpTimeoutException e) {
                return Result.failure(
                        Outcome.AMBIGUOUS,
                        null,
                        "Délai dépassé : le fournisseur a peut-être traité l'ordre.",
                        requestAudit,
                        null);
            } catch (IOException e) {
                return Result.failure(
                        Outcome.AMBIGUOUS,
                        null,
                        "Connexion interrompue : " + e.getClass().getSimpleName(),
                        requestAudit,
                        null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.failure(
                        Outcome.AMBIGUOUS, null, "Appel interrompu.", requestAudit, null);
            }
            String responseAudit = mask(response.body());
            if (response.statusCode() == 401 && attempt == 0) {
                token = null; // jeton refusé : l'ordre n'a pas été traité, on se reconnecte une
                // fois
                Result<Void> relogin = ensureToken();
                if (!relogin.isOk()) {
                    return Result.failure(
                            relogin.outcome(),
                            relogin.code(),
                            relogin.message(),
                            requestAudit,
                            responseAudit);
                }
                continue;
            }
            return classify(
                    response, parser, expected, requestAudit, responseAudit, statusOptional);
        }
        return Result.failure(
                Outcome.NOT_PROCESSED, "1002", "Authentification refusée.", requestAudit, null);
    }

    private <T> Result<T> classify(
            HttpResponse<String> response,
            Function<JsonNode, T> parser,
            String expected,
            String requestAudit,
            String responseAudit,
            boolean statusOptional) {
        int httpStatus = response.statusCode();
        if (httpStatus == 401 || httpStatus == 429) {
            return Result.failure(
                    Outcome.NOT_PROCESSED,
                    String.valueOf(httpStatus),
                    "Refusé avant traitement (HTTP " + httpStatus + ").",
                    requestAudit,
                    responseAudit);
        }
        if (httpStatus >= 500) {
            return Result.failure(
                    Outcome.AMBIGUOUS,
                    String.valueOf(httpStatus),
                    "Erreur du fournisseur (HTTP " + httpStatus + ").",
                    requestAudit,
                    responseAudit);
        }
        JsonNode node;
        try {
            node = json.readTree(response.body());
        } catch (RuntimeException e) {
            return Result.failure(
                    Outcome.AMBIGUOUS,
                    null,
                    "Réponse illisible du fournisseur.",
                    requestAudit,
                    responseAudit);
        }
        // La spécification orthographie « stauts » dans certains exemples : on accepte les deux.
        JsonNode statusNode = node.has("status") ? node.get("status") : node.get("stauts");
        String code = statusNode == null || statusNode.isNull() ? null : statusNode.asString();
        String message = firstText(node, "message", "messages");
        if (code == null && statusOptional) {
            T value = parser.apply(node);
            return value == null
                    ? Result.failure(
                            Outcome.AMBIGUOUS,
                            null,
                            "Liste illisible.",
                            requestAudit,
                            responseAudit)
                    : Result.ok(value, requestAudit, responseAudit);
        }
        if (code == null) {
            return Result.failure(
                    Outcome.AMBIGUOUS,
                    null,
                    "Réponse sans code de statut.",
                    requestAudit,
                    responseAudit);
        }
        if (isConfirmationAlreadyDone(code, node)) {
            @SuppressWarnings("unchecked")
            T done = (T) Boolean.TRUE;
            return Result.ok(done, requestAudit, responseAudit);
        }
        if ("1005".equals(code)) {
            return Result.failure(Outcome.AMBIGUOUS, code, message, requestAudit, responseAudit);
        }
        if ("1002".equals(code)) {
            return Result.failure(
                    Outcome.NOT_PROCESSED, code, message, requestAudit, responseAudit);
        }
        if (REJECTION_CODES.contains(code)) {
            return Result.failure(Outcome.REJECTED, code, message, requestAudit, responseAudit);
        }
        boolean success =
                SUCCESS_CODES.contains(code) || (isStatusQuery(node) && isLifecycleCode(code));
        if (!success) {
            // 202/203/204/205/206 hors consultation de statut, ou code inconnu : on ne devine pas.
            Outcome outcome = "204".equals(code) ? Outcome.NOT_PROCESSED : Outcome.AMBIGUOUS;
            return Result.failure(outcome, code, message, requestAudit, responseAudit);
        }
        T value = parser.apply(node);
        if (value == null) {
            return Result.failure(
                    Outcome.AMBIGUOUS,
                    code,
                    "Réponse de succès sans " + expected + ".",
                    requestAudit,
                    responseAudit);
        }
        return Result.ok(value, requestAudit, responseAudit);
    }

    /** Confirmation : « déjà confirmé » (1016) vaut succès. */
    private static boolean isConfirmationAlreadyDone(String code, JsonNode node) {
        return "1016".equals(code);
    }

    private static boolean isStatusQuery(JsonNode node) {
        return node.path("transaction").has("Status");
    }

    private static boolean isLifecycleCode(String code) {
        return Set.of("202", "203", "204", "205", "206").contains(code);
    }

    private Result<Void> ensureToken() {
        Token current = token;
        if (current != null && current.expiresAt().isAfter(clock.instant().plus(TOKEN_MARGIN))) {
            return Result.ok(null, "login", "token valide");
        }
        synchronized (loginLock) {
            current = token;
            if (current != null
                    && current.expiresAt().isAfter(clock.instant().plus(TOKEN_MARGIN))) {
                return Result.ok(null, "login", "token valide");
            }
            ObjectNode body = json.createObjectNode();
            body.put("email", config.email());
            body.put("password", config.password());
            try {
                HttpResponse<String> response =
                        http.send(
                                request("POST", "/login", body, false),
                                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                JsonNode node = json.readTree(response.body());
                String accessToken = text(node, "access_token");
                if (response.statusCode() >= 400 || accessToken == null) {
                    return Result.failure(
                            Outcome.NOT_PROCESSED,
                            text(node, "status"),
                            "Authentification refusée par Wace.",
                            "login",
                            mask(response.body()));
                }
                long seconds = node.has("expires_in") ? node.get("expires_in").asLong(3600) : 3600;
                token = new Token(accessToken, clock.instant().plusSeconds(seconds));
                return Result.ok(null, "login", "token obtenu");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.failure(
                        Outcome.NOT_PROCESSED, null, "Connexion interrompue.", "login", null);
            } catch (IOException | RuntimeException e) {
                // Aucun ordre n'a été envoyé : l'échec de connexion est toujours « non traité ».
                return Result.failure(
                        Outcome.NOT_PROCESSED,
                        null,
                        "Connexion à Wace impossible : " + e.getClass().getSimpleName(),
                        "login",
                        null);
            }
        }
    }

    private HttpRequest request(String method, String path, ObjectNode body) {
        return request(method, path, body, true);
    }

    private HttpRequest request(
            String method, String path, ObjectNode body, boolean authenticated) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(config.baseUrl().replaceAll("/+$", "") + path))
                        .timeout(config.requestTimeout())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json");
        if (authenticated) {
            builder.header("Authorization", "Bearer " + token.value());
        }
        if ("GET".equals(method)) {
            return builder.GET().build();
        }
        return builder.method(
                        method,
                        HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
    }

    // ------------------------------------------------------------------ utilitaires

    static ProviderStatus parseStatus(String raw) {
        return switch (raw.trim().toUpperCase(Locale.ROOT).replace('_', ' ')) {
            case "PENDING" -> ProviderStatus.PENDING;
            case "WAITING CONFIRMATION" -> ProviderStatus.WAITING_CONFIRMATION;
            case "PROCESSING" -> ProviderStatus.PROCESSING;
            case "PAID" -> ProviderStatus.PAID;
            case "CANCEL", "CANCELED", "CANCELLED" -> ProviderStatus.CANCEL;
            case "LOCKED" -> ProviderStatus.LOCKED;
            default -> ProviderStatus.UNKNOWN;
        };
    }

    /**
     * « 1080.000XAF » → 1080.000 ; les montants arrivent tantôt en nombre, tantôt en texte avec la
     * devise.
     */
    static BigDecimal number(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        Matcher matcher = NUMBER.matcher(node.asString());
        return matcher.find() ? new BigDecimal(matcher.group()) : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.asString().isBlank()
                ? null
                : value.asString();
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isString()) {
                return value.asString();
            }
        }
        return null;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static void putIf(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    /**
     * Trace sans donnée sensible : numéros et jetons ne gardent que leurs 4 derniers caractères.
     */
    private String mask(ObjectNode body) {
        return json.writeValueAsString(maskNode(body));
    }

    private String mask(String raw) {
        try {
            return json.writeValueAsString(maskNode(json.readTree(raw)));
        } catch (RuntimeException e) {
            return "{\"unparseable\":true}";
        }
    }

    private JsonNode maskNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode copy = json.createObjectNode();
            node.properties()
                    .forEach(
                            entry -> {
                                if (MASKED_KEYS.contains(entry.getKey().toLowerCase(Locale.ROOT))
                                        && entry.getValue().isValueNode()) {
                                    String value = entry.getValue().asString();
                                    copy.put(
                                            entry.getKey(),
                                            value.length() <= 4
                                                    ? "****"
                                                    : "****" + value.substring(value.length() - 4));
                                } else {
                                    copy.set(entry.getKey(), maskNode(entry.getValue()));
                                }
                            });
            return copy;
        }
        if (node.isArray()) {
            var array = json.createArrayNode();
            node.forEach(item -> array.add(maskNode(item)));
            return array;
        }
        return node;
    }
}
