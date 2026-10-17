package com.wtc.migration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Contrôles de réconciliation ancien/nouveau. Chacun compare un chiffre calculé sur la source à
 * celui de la cible ; un écart est une erreur bloquante pour la bascule (l'écart doit être expliqué
 * par une anomalie rapportée, puis le contrôle relancé après décision).
 */
final class Controls {

    private final EtlContext ctx;
    private final Map<String, BigDecimal> openedByCurrency;
    private final List<String> failed = new ArrayList<>();

    Controls(EtlContext ctx, Map<String, BigDecimal> openedByCurrency) {
        this.ctx = ctx;
        this.openedByCurrency = openedByCurrency;
    }

    List<String> run() {
        ctx.report.stage("controls");
        rowCounts();
        balances();
        discrepancies();
        completedByMonth();
        logins();
        return List.copyOf(failed);
    }

    private void check(boolean ok, String name, String detail) {
        if (ok) {
            ctx.report.info("control", null, "OK : " + name + " — " + detail);
        } else {
            failed.add(name + " — " + detail);
            ctx.report.error("control", null, "ÉCART : " + name + " — " + detail, null);
        }
    }

    private long source(String sql) {
        return ctx.source.sql(sql).query(Long.class).single();
    }

    private void rowCounts() {
        count("users", source("SELECT COUNT(*) FROM user"), "users.migrated", "users.rejected");
        count(
                "clients titulaires",
                source("SELECT COUNT(*) FROM customer WHERE customeralt IS NULL"),
                "customers.migrated",
                "customers.rejected");
        count(
                "bénéficiaires",
                source("SELECT COUNT(*) FROM contactcustomer"),
                "parties.beneficiaries",
                "beneficiaries.rejected");
        count(
                "transactions",
                source("SELECT COUNT(*) FROM transaction"),
                "transfers.migrated",
                "transfers.rejected");
        count(
                "recharges",
                source("SELECT COUNT(*) FROM prefunding"),
                "topups.migrated",
                "topups.rejected");
        count(
                "jetons web",
                source("SELECT COUNT(*) FROM web_token"),
                "web_tokens.migrated",
                "web_tokens.rejected");
        count(
                "journal de connexions",
                source("SELECT COUNT(*) FROM login_audit"),
                "login_audit.migrated",
                null);
        count(
                "journal d'actions",
                source("SELECT COUNT(*) FROM user_action_log"),
                "audit_log.migrated",
                null);
        count(
                "pays",
                source("SELECT COUNT(*) FROM country"),
                "countries.migrated",
                "countries.rejected",
                "countries.merged");
        long targetTransfers =
                ctx.target
                        .sql("SELECT COUNT(*) FROM transfers WHERE legacy_id IS NOT NULL")
                        .query(Long.class)
                        .single();
        check(
                targetTransfers == ctx.report.counter("transfers.migrated"),
                "transferts présents en base",
                targetTransfers
                        + " en base, "
                        + ctx.report.counter("transfers.migrated")
                        + " migrés");
    }

    private void count(
            String name, long expected, String migrated, String rejected, String... extra) {
        long got =
                ctx.report.counter(migrated)
                        + (rejected == null ? 0 : ctx.report.counter(rejected));
        for (String e : extra) {
            got += ctx.report.counter(e);
        }
        check(
                expected == got,
                "lignes « " + name + " »",
                "source "
                        + expected
                        + " = migrées "
                        + ctx.report.counter(migrated)
                        + " + rejetées "
                        + (got - ctx.report.counter(migrated)));
    }

    /** Somme des soldes par devise : source (positifs) contre portefeuilles migrés, à 0,01 près. */
    private void balances() {
        Map<String, BigDecimal> expected = new TreeMap<>(openedByCurrency);
        Map<String, BigDecimal> actual = new TreeMap<>();
        ctx.target
                .sql(
                        "SELECT currency_code, sum(balance) AS total FROM wallets GROUP BY"
                                + " currency_code")
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getBigDecimal(2)))
                .list()
                .forEach(e -> actual.put(e.getKey(), e.getValue()));
        Map<String, BigDecimal> all = new HashMap<>(expected);
        actual.forEach((k, v) -> all.putIfAbsent(k, BigDecimal.ZERO));
        for (var e : new TreeMap<>(all).entrySet()) {
            BigDecimal exp = expected.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal act = actual.getOrDefault(e.getKey(), BigDecimal.ZERO);
            check(
                    exp.subtract(act).abs().compareTo(new BigDecimal("0.01")) <= 0,
                    "somme des soldes " + e.getKey(),
                    "ancien (positifs) "
                            + exp.setScale(2, RoundingMode.HALF_UP)
                            + " / nouveau "
                            + act.setScale(2, RoundingMode.HALF_UP));
        }
    }

    private void discrepancies() {
        long n =
                ctx.target
                        .sql("SELECT count(*) FROM wallet_balance_discrepancies")
                        .query(Long.class)
                        .single();
        check(n == 0, "soldes = grand livre", n + " portefeuille(s) en écart");
    }

    /** Montants des transactions terminées par mois : source (hors rejetées) contre cible. */
    private void completedByMonth() {
        Map<String, BigDecimal> expected = new TreeMap<>();
        ctx.rows(
                        "SELECT id, montant, datetransaction FROM transaction WHERE LOWER(status)"
                                + " IN ('effectue', 'effectué', 'paid', 'success')")
                .forEach(
                        r -> {
                            if (!ctx.rejectedTransactions.contains(r.idOrZero())
                                    && r.decimal("montant") != null
                                    && r.instant("datetransaction") != null) {
                                expected.merge(
                                        month(r.instant("datetransaction")),
                                        r.decimal("montant").setScale(4, RoundingMode.HALF_UP),
                                        BigDecimal::add);
                            }
                        });
        Map<String, BigDecimal> actual = new TreeMap<>();
        ctx.target
                .sql(
                        "SELECT to_char(created_at AT TIME ZONE 'Africa/Brazzaville', 'YYYY-MM') AS"
                            + " m, sum(source_amount) FROM transfers WHERE status = 'COMPLETED' AND"
                            + " legacy_id IS NOT NULL GROUP BY 1")
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getBigDecimal(2)))
                .list()
                .forEach(e -> actual.put(e.getKey(), e.getValue()));
        Map<String, BigDecimal> months = new TreeMap<>(expected);
        actual.forEach((k, v) -> months.putIfAbsent(k, BigDecimal.ZERO));
        List<String> bad = new ArrayList<>();
        for (String m : months.keySet()) {
            BigDecimal exp = expected.getOrDefault(m, BigDecimal.ZERO);
            BigDecimal act = actual.getOrDefault(m, BigDecimal.ZERO);
            if (exp.subtract(act).abs().compareTo(new BigDecimal("0.01")) > 0) {
                bad.add(
                        m
                                + " ("
                                + exp.setScale(2, RoundingMode.HALF_UP)
                                + " ≠ "
                                + act.setScale(2, RoundingMode.HALF_UP)
                                + ")");
            }
        }
        check(
                bad.isEmpty(),
                "montants terminés par mois",
                bad.isEmpty() ? months.size() + " mois identiques" : "écarts : " + bad);
    }

    private static String month(java.time.Instant at) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")
                .withZone(Row.LEGACY_ZONE)
                .format(at);
    }

    /**
     * Tous les comptes migrés avec un hachage bcrypt doivent pouvoir se connecter (statut actif).
     */
    private void logins() {
        long migrated = ctx.report.counter("users.migrated");
        long active =
                ctx.target
                        .sql(
                                "SELECT count(*) FROM users WHERE legacy_id IS NOT NULL AND status"
                                    + " = 'ACTIVE' AND (password_hash LIKE '$2%' OR password_hash"
                                    + " LIKE '$argon2%')")
                        .query(Long.class)
                        .single();
        long suspended =
                ctx.target
                        .sql(
                                "SELECT count(*) FROM users WHERE legacy_id IS NOT NULL AND status"
                                        + " <> 'ACTIVE'")
                        .query(Long.class)
                        .single();
        check(
                active + suspended == migrated,
                "comptes pouvant se connecter",
                active
                        + " actifs avec hachage valide, "
                        + suspended
                        + " suspendus, sur "
                        + migrated
                        + " migrés");
    }
}
