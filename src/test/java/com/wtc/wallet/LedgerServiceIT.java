package com.wtc.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wtc.identity.IdentityFixtures;
import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.shared.error.DomainException;
import com.wtc.support.AbstractIT;
import com.wtc.support.Api;
import com.wtc.support.Sessions;
import com.wtc.wallet.application.AccountRef;
import com.wtc.wallet.application.LedgerKind;
import com.wtc.wallet.application.LedgerService;
import com.wtc.wallet.application.PostingLeg;
import com.wtc.wallet.application.PostingRequest;
import com.wtc.wallet.application.PostingResult;
import com.wtc.wallet.application.WalletService;
import com.wtc.wallet.application.port.WalletRepository.WalletRow;
import java.math.BigDecimal;
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
 * Le grand livre sous contrainte : idempotence, atomicité, concurrence, solde jamais négatif,
 * équilibre.
 */
class LedgerServiceIT extends AbstractIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;
    @Autowired LedgerService ledger;
    @Autowired WalletService wallets;

    IdentityFixtures fx;
    UUID customer;

    @BeforeAll
    static void seed(
            @Autowired JdbcClient jdbc,
            @Autowired UserRepository users,
            @Autowired PasswordHasher hasher) {
        new IdentityFixtures(jdbc, users, hasher).seedCountry();
    }

    @AfterAll
    static void clean(
            @Autowired JdbcClient jdbc,
            @Autowired UserRepository users,
            @Autowired PasswordHasher hasher) {
        new IdentityFixtures(jdbc, users, hasher).cleanUp();
    }

    @BeforeEach
    void init() {
        fx = new IdentityFixtures(jdbc, users, hasher);
        customer = UUID.fromString(Sessions.customer(new Api(mvc), fx, "grandlivre").customerId());
    }

    private WalletRow wallet(String currency) {
        return wallets.ensureWallet(customer, currency);
    }

    private WalletRow wallet(UUID owner, String currency) {
        return wallets.ensureWallet(owner, currency);
    }

    private BigDecimal balance(WalletRow w) {
        return jdbc.sql("SELECT balance FROM wallets WHERE id = :id")
                .param("id", w.id())
                .query(BigDecimal.class)
                .single();
    }

    private PostingResult fund(WalletRow w, String amount, String reference) {
        BigDecimal value = new BigDecimal(amount);
        return ledger.post(
                new PostingRequest(
                        reference,
                        LedgerKind.TOP_UP,
                        "Recharge",
                        List.of(
                                PostingLeg.debit(
                                        AccountRef.platform("PLATFORM_TOPUP_CLEARING"),
                                        value,
                                        w.currency()),
                                PostingLeg.credit(AccountRef.wallet(w.id()), value, w.currency())),
                        null,
                        null,
                        null));
    }

    private PostingRequest spend(WalletRow w, String amount, String reference) {
        BigDecimal value = new BigDecimal(amount);
        return new PostingRequest(
                reference,
                LedgerKind.TRANSFER_DEBIT,
                "Transfert",
                List.of(
                        PostingLeg.debit(AccountRef.wallet(w.id()), value, w.currency()),
                        PostingLeg.credit(
                                AccountRef.platform("PLATFORM_PAYOUT_CLEARING"),
                                value,
                                w.currency())),
                null,
                null,
                null);
    }

    private long discrepancies() {
        return jdbc.sql("SELECT count(*) FROM wallet_balance_discrepancies")
                .query(Long.class)
                .single();
    }

    private String ref(String prefix) {
        return prefix + ":" + UUID.randomUUID();
    }

    @Test
    void aTopUpCreditsTheWalletAndLeavesTwoBalancedEntries() {
        WalletRow w = wallet("XAF");
        PostingResult result = fund(w, "10000", ref("topup"));

        assertThat(result.replayed()).isFalse();
        assertThat(balance(w)).isEqualByComparingTo("10000");
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM ledger_entries WHERE"
                                                + " ledger_transaction_id = :t")
                                .param("t", result.ledgerTransactionId())
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
        assertThat(discrepancies()).isZero();
    }

    @Test
    void replayingTheSameReferenceChangesNothingAndADifferentContentIsRefused() {
        WalletRow w = wallet("XAF");
        String reference = ref("topup");
        PostingResult first = fund(w, "5000", reference);

        PostingResult again = fund(w, "5000", reference);
        assertThat(again.replayed()).isTrue();
        assertThat(again.ledgerTransactionId()).isEqualTo(first.ledgerTransactionId());
        assertThat(balance(w)).as("crédité une seule fois").isEqualByComparingTo("5000");

        assertThatThrownBy(() -> fund(w, "9999", reference))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        e -> assertThat(e.code().name()).isEqualTo("LEDGER_CONFLICT"));
        assertThat(balance(w)).isEqualByComparingTo("5000");
    }

    @Test
    void anOverdraftIsRefusedAndLeavesNoTrace() {
        WalletRow w = wallet("XAF");
        fund(w, "1000", ref("topup"));
        String reference = ref("debit");

        assertThatThrownBy(() -> ledger.post(spend(w, "1001", reference)))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        e -> assertThat(e.code().name()).isEqualTo("INSUFFICIENT_FUNDS"));

        assertThat(balance(w)).isEqualByComparingTo("1000");
        assertThat(
                        jdbc.sql("SELECT count(*) FROM ledger_transactions WHERE reference = :r")
                                .param("r", reference)
                                .query(Long.class)
                                .single())
                .as("la transaction a été annulée")
                .isZero();
        ledger.post(spend(w, "1000", ref("debit")));
        assertThat(balance(w))
                .as("le solde peut tomber à zéro, pas en dessous")
                .isEqualByComparingTo("0");
    }

    @Test
    void aFailureOnTheLastLegUndoesTheFirstOnes() {
        WalletRow source = wallet("XAF");
        UUID other =
                UUID.fromString(Sessions.customer(new Api(mvc), fx, "destinataire").customerId());
        WalletRow target = wallet(other, "XAF");
        fund(source, "2000", ref("topup"));
        jdbc.sql("UPDATE wallets SET status = 'CLOSED' WHERE id = :id")
                .param("id", target.id())
                .update();

        PostingRequest request =
                new PostingRequest(
                        ref("p2p"),
                        LedgerKind.CORRECTION,
                        "Virement interne",
                        List.of(
                                PostingLeg.debit(
                                        AccountRef.wallet(source.id()),
                                        new BigDecimal("500"),
                                        "XAF"),
                                PostingLeg.credit(
                                        AccountRef.wallet(target.id()),
                                        new BigDecimal("500"),
                                        "XAF")),
                        null,
                        null,
                        null);
        assertThatThrownBy(() -> ledger.post(request))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        e -> assertThat(e.code().name()).isEqualTo("WALLET_NOT_ACTIVE"));

        assertThat(balance(source))
                .as("le débit de la première écriture est annulé")
                .isEqualByComparingTo("2000");
        assertThat(
                        jdbc.sql("SELECT count(*) FROM ledger_transactions WHERE reference = :r")
                                .param("r", request.reference())
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(discrepancies()).isZero();
    }

    @Test
    void aFrozenWalletStillReceivesMoneyButCannotSpendIt() {
        WalletRow w = wallet("XAF");
        fund(w, "1000", ref("topup"));
        jdbc.sql("UPDATE wallets SET status = 'FROZEN' WHERE id = :id")
                .param("id", w.id())
                .update();

        fund(w, "500", ref("topup"));
        assertThat(balance(w)).isEqualByComparingTo("1500");
        assertThatThrownBy(() -> ledger.post(spend(w, "100", ref("debit"))))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        e -> assertThat(e.code().name()).isEqualTo("WALLET_NOT_ACTIVE"));
        assertThat(balance(w)).isEqualByComparingTo("1500");
    }

    @Test
    void aMovementInTheWrongCurrencyIsRefused() {
        WalletRow w = wallet("XAF");
        assertThatThrownBy(
                        () ->
                                ledger.post(
                                        new PostingRequest(
                                                ref("bad"),
                                                LedgerKind.TOP_UP,
                                                "x",
                                                List.of(
                                                        PostingLeg.debit(
                                                                AccountRef.platform(
                                                                        "PLATFORM_TOPUP_CLEARING"),
                                                                new BigDecimal("10.00"),
                                                                "EUR"),
                                                        PostingLeg.credit(
                                                                AccountRef.wallet(w.id()),
                                                                new BigDecimal("10.00"),
                                                                "EUR")),
                                                null,
                                                null,
                                                null)))
                .isInstanceOf(DomainException.class);
        assertThat(balance(w)).isEqualByComparingTo("0");
    }

    @Test
    void platformAccountsAreCreatedPerCurrencyOnDemand() {
        WalletRow eur = wallet("EUR");
        fund(eur, "25.50", ref("topup"));

        assertThat(
                        jdbc.sql(
                                        "SELECT currency_code FROM ledger_accounts WHERE code ="
                                                + " 'PLATFORM_TOPUP_CLEARING.EUR'")
                                .query(String.class)
                                .single()
                                .strip())
                .isEqualTo("EUR");
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM ledger_accounts WHERE code ="
                                                + " 'PLATFORM_TOPUP_CLEARING'")
                                .query(Long.class)
                                .single())
                .as("le compte XAF historique est inchangé")
                .isEqualTo(1);
        assertThat(balance(eur)).isEqualByComparingTo("25.50");
        assertThat(wallet("XAF").id()).as("un portefeuille par devise").isNotEqualTo(eur.id());
    }

    @Test
    void concurrentDebitsNeverOverdrawTheWallet() throws Exception {
        WalletRow w = wallet("XAF");
        fund(w, "1000", ref("topup"));
        int attempts = 25; // 25 × 100 demandés pour 1000 disponibles

        List<Object> outcomes =
                runConcurrently(
                        attempts,
                        i ->
                                () -> {
                                    try {
                                        ledger.post(spend(w, "100", "debit:" + w.id() + ":" + i));
                                        return "OK";
                                    } catch (DomainException e) {
                                        return e.code().name();
                                    }
                                });

        assertThat(outcomes.stream().filter("OK"::equals).count())
                .as("exactement 10 débits passent")
                .isEqualTo(10);
        assertThat(outcomes.stream().filter("INSUFFICIENT_FUNDS"::equals).count()).isEqualTo(15);
        assertThat(balance(w)).isEqualByComparingTo("0");
        assertThat(discrepancies()).isZero();
    }

    @Test
    void theSameReferenceSubmittedAtOnceDebitsOnlyOnce() throws Exception {
        WalletRow w = wallet("XAF");
        fund(w, "1000", ref("topup"));
        String reference = "debit:once:" + w.id();

        List<Object> outcomes =
                runConcurrently(8, i -> () -> ledger.post(spend(w, "300", reference)).replayed());

        assertThat(outcomes.stream().filter(Boolean.FALSE::equals).count())
                .as("une seule exécution réelle")
                .isEqualTo(1);
        assertThat(balance(w)).isEqualByComparingTo("700");
        assertThat(
                        jdbc.sql("SELECT count(*) FROM ledger_transactions WHERE reference = :r")
                                .param("r", reference)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void crossTransfersBetweenTwoWalletsNeitherDeadlockNorCreateMoney() throws Exception {
        WalletRow a = wallet("XAF");
        WalletRow b =
                wallet(
                        UUID.fromString(Sessions.customer(new Api(mvc), fx, "croise").customerId()),
                        "XAF");
        fund(a, "100000", ref("topup"));
        fund(b, "100000", ref("topup"));

        List<Object> outcomes =
                runConcurrently(
                        60,
                        i ->
                                () -> {
                                    WalletRow from = i % 2 == 0 ? a : b;
                                    WalletRow to = i % 2 == 0 ? b : a;
                                    ledger.post(
                                            new PostingRequest(
                                                    "cross:" + a.id() + ":" + i,
                                                    LedgerKind.CORRECTION,
                                                    "Virement croisé",
                                                    List.of(
                                                            PostingLeg.debit(
                                                                    AccountRef.wallet(from.id()),
                                                                    new BigDecimal("100"),
                                                                    "XAF"),
                                                            PostingLeg.credit(
                                                                    AccountRef.wallet(to.id()),
                                                                    new BigDecimal("100"),
                                                                    "XAF")),
                                                    null,
                                                    null,
                                                    null));
                                    return "OK";
                                });

        assertThat(outcomes).as("aucun interblocage ni erreur").allMatch("OK"::equals);
        assertThat(balance(a).add(balance(b)))
                .as("la somme est conservée")
                .isEqualByComparingTo("200000");
        assertThat(discrepancies()).isZero();
    }

    @Test
    void aLongRandomSequenceKeepsEveryBalanceEqualToItsLedger() {
        WalletRow w = wallet("XAF");
        java.util.Random random = new java.util.Random(42);
        BigDecimal expected = BigDecimal.ZERO;
        for (int i = 0; i < 150; i++) {
            BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(5000));
            if (random.nextBoolean()) {
                fund(w, amount.toPlainString(), "rand:in:" + w.id() + ":" + i);
                expected = expected.add(amount);
            } else {
                try {
                    ledger.post(spend(w, amount.toPlainString(), "rand:out:" + w.id() + ":" + i));
                    expected = expected.subtract(amount);
                } catch (DomainException e) {
                    assertThat(e.code().name()).isEqualTo("INSUFFICIENT_FUNDS");
                    assertThat(amount).isGreaterThan(expected);
                }
            }
            assertThat(balance(w)).isEqualByComparingTo(expected);
        }
        assertThat(discrepancies()).isZero();
        assertThat(wallets.availableBalance(customer, "XAF")).isEqualByComparingTo(expected);
    }

    private <T> List<Object> runConcurrently(
            int count, java.util.function.IntFunction<Callable<T>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(count, 16));
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
}
