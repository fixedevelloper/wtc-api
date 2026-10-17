package com.wtc.payment.infrastructure;

import com.wtc.payment.application.PayoutGateway;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Faux fournisseur <b>pilotable</b> : développement local et tests. Il se comporte comme Wace
 * décrit par la spécification (création puis confirmation, statuts, refus, délais) et enregistre
 * tous les appels. Sélectionné par {@code wtc.payment.provider=fake}, jamais en production.
 */
public final class FakePayoutGateway implements PayoutGateway {

    /** Appel enregistré. */
    public record Call(String operation, String argument) {}

    private final AtomicLong sequence = new AtomicLong(1000);
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final Deque<Result<OrderCreated>> scriptedCreates = new ArrayDeque<>();
    private final Deque<Result<Boolean>> scriptedConfirms = new ArrayDeque<>();
    private final Map<String, Deque<Result<OrderStatus>>> scriptedStatuses =
            new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> amounts = new ConcurrentHashMap<>();
    private volatile BigDecimal amountReceivedOverride;
    private volatile BigDecimal feeToCharge = new BigDecimal("350");

    /** Création de chaque ordre (pour le paiement automatique de l'essai de bout en bout). */
    private final Map<String, java.time.Instant> createdAt = new ConcurrentHashMap<>();

    /**
     * Si non nul, un ordre devient PAID ce délai après sa création ; sinon il reste PROCESSING
     * (tests).
     */
    private final java.time.Duration autoCompleteAfter;

    public FakePayoutGateway() {
        this(null);
    }

    /**
     * @param autoCompleteAfter délai avant paiement automatique des ordres (développement), null
     *     pour désactiver
     */
    public FakePayoutGateway(java.time.Duration autoCompleteAfter) {
        this.autoCompleteAfter = autoCompleteAfter;
    }

    // ------------------------------------------------------------------ pilotage

    /** Prochaine création : ce résultat, une fois. */
    public synchronized void scriptNextCreate(Result<OrderCreated> result) {
        scriptedCreates.add(result);
    }

    public synchronized void scriptNextConfirm(Result<Boolean> result) {
        scriptedConfirms.add(result);
    }

    /**
     * Prochains statuts renvoyés pour cette référence, dans l'ordre ; ensuite le dernier se répète.
     */
    public void scriptStatus(String reference, Result<OrderStatus> result) {
        scriptedStatuses.computeIfAbsent(reference, k -> new ArrayDeque<>()).add(result);
    }

    /**
     * Montant annoncé au bénéficiaire pour les prochaines créations (null : égal au montant
     * envoyé).
     */
    public void announceAmountReceived(BigDecimal amount) {
        this.amountReceivedOverride = amount;
    }

    public void chargeProviderFee(BigDecimal fee) {
        this.feeToCharge = fee;
    }

    public List<Call> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    public long countCalls(String operation) {
        return calls().stream().filter(c -> c.operation().equals(operation)).count();
    }

    public void reset() {
        calls.clear();
        synchronized (this) {
            scriptedCreates.clear();
            scriptedConfirms.clear();
        }
        scriptedStatuses.clear();
        amounts.clear();
        createdAt.clear();
        servicesByCountry.clear();
        banksByCountry.clear();
        amountReceivedOverride = null;
        feeToCharge = new BigDecimal("350");
    }

    // ------------------------------------------------------------------ port

    @Override
    public Result<String> createSender(SenderData data) {
        calls.add(new Call("createSender", data.firstName() + " " + data.lastName()));
        return Result.ok("S" + sequence.incrementAndGet(), "{}", "{}");
    }

    @Override
    public Result<String> createBeneficiary(String senderCode, BeneficiaryData data) {
        calls.add(new Call("createBeneficiary", senderCode));
        return Result.ok("B" + sequence.incrementAndGet(), "{}", "{}");
    }

    @Override
    public Result<OrderCreated> createOrder(PayoutOrder order) {
        calls.add(new Call("createOrder", order.amountToPay().toPlainString()));
        Result<OrderCreated> scripted;
        synchronized (this) {
            scripted = scriptedCreates.poll();
        }
        if (scripted != null) {
            return scripted;
        }
        String reference = "WPPX" + sequence.incrementAndGet();
        BigDecimal received =
                amountReceivedOverride != null ? amountReceivedOverride : order.amountToPay();
        amounts.put(reference, received);
        createdAt.put(reference, java.time.Instant.now());
        return Result.ok(
                new OrderCreated(
                        reference, received, feeToCharge, order.amountToPay().add(feeToCharge)),
                "{\"fake\":true}",
                "{\"reference\":\"" + reference + "\"}");
    }

    @Override
    public Result<Boolean> confirm(String reference) {
        calls.add(new Call("confirm", reference));
        Result<Boolean> scripted;
        synchronized (this) {
            scripted = scriptedConfirms.poll();
        }
        return scripted != null ? scripted : Result.ok(Boolean.TRUE, "{}", "{}");
    }

    @Override
    public Result<OrderStatus> status(String reference) {
        calls.add(new Call("status", reference));
        Deque<Result<OrderStatus>> script = scriptedStatuses.get(reference);
        if (script != null && !script.isEmpty()) {
            return script.size() > 1 ? script.poll() : script.peek();
        }
        java.time.Instant created = createdAt.get(reference);
        if (autoCompleteAfter != null
                && created != null
                && java.time.Instant.now().isAfter(created.plus(autoCompleteAfter))) {
            return Result.ok(
                    new OrderStatus(
                            ProviderStatus.PAID, "PAID", amounts.get(reference), feeToCharge),
                    "{}",
                    "{}");
        }
        return Result.ok(
                new OrderStatus(
                        ProviderStatus.PROCESSING,
                        "PROCESSING",
                        amounts.get(reference),
                        feeToCharge),
                "{}",
                "{}");
    }

    private final Map<String, List<PayoutService>> servicesByCountry = new ConcurrentHashMap<>();
    private final Map<String, List<ProviderBank>> banksByCountry = new ConcurrentHashMap<>();
    private volatile Map<String, String> relationList =
            Map.of("Brother", "Brother", "Spouse", "Spouse");
    private volatile Map<String, String> originList =
            Map.of("Salary", "Salary", "Savings", "Savings");
    private volatile Map<String, String> reasonList =
            Map.of("Family Maintainance", "Family Maintainance");

    /** Services (et banques) que le faux fournisseur annonce pour ce pays. */
    public void offerServices(
            String country, List<PayoutService> services, List<ProviderBank> banks) {
        servicesByCountry.put(country, List.copyOf(services));
        banksByCountry.put(country, List.copyOf(banks));
    }

    @Override
    public Result<List<PayoutService>> payoutServices(String country, String currency) {
        calls.add(new Call("payoutServices", country));
        return Result.ok(servicesByCountry.getOrDefault(country, List.of()), "{}", "{}");
    }

    @Override
    public Result<String> payerCode(String country, String service, String currency) {
        calls.add(new Call("payerCode", country + "/" + service));
        return Result.ok("PC-" + country + "-" + service, "{}", "{}");
    }

    @Override
    public Result<List<ProviderBank>> banks(String country, String payerCode) {
        calls.add(new Call("banks", country));
        return Result.ok(banksByCountry.getOrDefault(country, List.of()), "{}", "{}");
    }

    @Override
    public Result<Map<String, String>> relations() {
        calls.add(new Call("relations", ""));
        return Result.ok(relationList, "{}", "{}");
    }

    @Override
    public Result<Map<String, String>> originFunds(String businessType) {
        calls.add(new Call("originFunds", businessType));
        return Result.ok(originList, "{}", "{}");
    }

    @Override
    public Result<Map<String, String>> reasons(String businessType) {
        calls.add(new Call("reasons", businessType));
        return Result.ok(reasonList, "{}", "{}");
    }

    @Override
    public Result<PartnerBalance> balance() {
        calls.add(new Call("balance", ""));
        return Result.ok(
                new PartnerBalance("FAKE AGENCY", new BigDecimal("100000000"), "XAF"), "{}", "{}");
    }
}
