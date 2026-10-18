package com.wtc.identity;

import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.identity.domain.Role;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Jeu de données des tests d'identité : un pays actif et des comptes créés directement en base. */
public final class IdentityFixtures {

    public static final String PASSWORD = "cheval agrafe pile bleu";
    public static final String COUNTRY = "ZZ";

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final PasswordHasher hasher;

    public IdentityFixtures(JdbcClient jdbc, UserRepository users, PasswordHasher hasher) {
        this.jdbc = jdbc;
        this.users = users;
        this.hasher = hasher;
    }

    public void seedCountry() {
        jdbc.sql(
                        """
                        INSERT INTO countries (code, iso3, name, currency_code, can_send, can_receive, enabled)
                        VALUES ('ZZ', 'ZZZ', 'Pays de test', 'XAF', true, true, true) ON CONFLICT DO NOTHING
                        """)
                .update();
    }

    /** Pays d'envoi (ZZ), de réception (YY), sans réception (WW), banques et opérateurs de test. */
    public Catalog seedCatalog() {
        seedCountry();
        jdbc.sql(
                        """
                        INSERT INTO countries (code, iso3, name, currency_code, can_send, can_receive, enabled) VALUES
                            ('YY', 'YYY', 'Pays de réception', 'XOF', false, true, true),
                            ('WW', 'WWW', 'Pays sans réception', 'XOF', true, false, true)
                        ON CONFLICT DO NOTHING
                        """)
                .update();
        long bankZz = insertBank("ZZ", "Banque ZZ", "BZZ");
        long bankYy = insertBank("YY", "Banque YY", "BYY");
        long operatorYy = insertOperator("Mobile YY", "MYY", true);
        long operatorWithoutPayout = insertOperator("Mobile sans envoi", "MNP", false);
        return new Catalog(bankZz, bankYy, operatorYy, operatorWithoutPayout);
    }

    private long insertBank(String country, String name, String shortCode) {
        return jdbc.sql(
                        "INSERT INTO banks (country_code, name, short_code) VALUES (:c, :n, :s)"
                                + " RETURNING id")
                .param("c", country)
                .param("n", name)
                .param("s", shortCode)
                .query(Long.class)
                .single();
    }

    private long insertOperator(String name, String shortCode, boolean payout) {
        return jdbc.sql(
                        "INSERT INTO mobile_operators (country_code, name, short_code,"
                                + " supports_payout) VALUES ('YY', :n, :s, :p) RETURNING id")
                .param("n", name)
                .param("s", shortCode)
                .param("p", payout)
                .query(Long.class)
                .single();
    }

    public record Catalog(long bankZz, long bankYy, long operatorYy, long operatorWithoutPayout) {}

    /**
     * Efface tout ce que les tests d'identité ont créé (TRUNCATE : les journaux d'audit sont
     * immuables ligne à ligne).
     */
    public void cleanUp() {
        // Pas de CASCADE : il viderait aussi les tables de référence qui pointent vers users
        // (comptes du grand livre...).
        jdbc.sql("TRUNCATE login_audit, audit_log, outbox_events, notification_outbox").update();
        jdbc.sql("DELETE FROM web_tokens").update();
        // Grand livre : immuable ligne à ligne, donc TRUNCATE ; CASCADE ne vide que les tables
        // transactionnelles qui le référencent.
        jdbc.sql("TRUNCATE ledger_entries, ledger_transactions, balance_adjustments CASCADE")
                .update();
        jdbc.sql("DELETE FROM ledger_accounts WHERE kind = 'CUSTOMER_WALLET' OR code LIKE '%.%'")
                .update();
        jdbc.sql("DELETE FROM top_ups").update();
        jdbc.sql("DELETE FROM wallets").update();
        jdbc.sql("DELETE FROM provider_party_refs").update();
        jdbc.sql("DELETE FROM payout_destinations").update();
        jdbc.sql("DELETE FROM parties").update();
        jdbc.sql("DELETE FROM kyc_documents").update();
        jdbc.sql("DELETE FROM customers").update();
        jdbc.sql("DELETE FROM mobile_operators WHERE country_code IN ('YY', 'ZZ', 'WW')").update();
        jdbc.sql("DELETE FROM banks WHERE country_code IN ('YY', 'ZZ', 'WW')").update();
        jdbc.sql("UPDATE platform_settings SET updated_by = NULL").update();
        jdbc.sql("DELETE FROM users").update();
        jdbc.sql("DELETE FROM countries WHERE code IN ('ZZ', 'YY', 'WW')").update();
    }

    public String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    public long createUser(String email, Set<Role> roles) {
        return users.insert(
                new UserRepository.NewUser(
                        email, hasher.hash(PASSWORD), "Utilisateur " + email, null, roles));
    }

    /** Compte avec un hachage bcrypt `$2y$` (format Symfony migré). */
    public long createLegacyBcryptUser(String email, String bcryptHash) {
        long id = createUser(email, Set.of(Role.CUSTOMER));
        jdbc.sql("UPDATE users SET password_hash = :h WHERE id = :id")
                .param("h", bcryptHash)
                .param("id", id)
                .update();
        return id;
    }

    public String passwordHashOf(String email) {
        return jdbc.sql("SELECT password_hash FROM users WHERE lower(email) = lower(:e)")
                .param("e", email)
                .query(String.class)
                .single();
    }
}
