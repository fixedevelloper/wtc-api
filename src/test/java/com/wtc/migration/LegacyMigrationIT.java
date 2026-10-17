package com.wtc.migration;

import static com.wtc.support.Api.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wtc.identity.IdentityFixtures;
import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.shared.security.SecretCipher;
import com.wtc.shared.storage.ObjectStorage;
import com.wtc.support.AbstractIT;
import com.wtc.support.Api;
import com.wtc.support.Api.Reply;
import com.wtc.wallet.application.LedgerService;
import com.wtc.wallet.application.WalletService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * ETL complet : ancienne base MySQL jetable (schéma réel de Symfony + jeu de données piégeux) vers
 * le nouveau schéma PostgreSQL.
 */
@ActiveProfiles("etl")
@TestPropertySource(properties = {"wtc.etl.run=false", "wtc.etl.dry-run=false"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LegacyMigrationIT extends AbstractIT {

    static final String PASSWORD = "Ancien-Mdp-123!";

    /**
     * MySQL 8.4 piloté par le pilote MariaDB (celui de l'ETL) : le pilote MySQL n'est pas une
     * dépendance du projet.
     */
    static final class LegacyMysql extends MySQLContainer {
        LegacyMysql() {
            super("mysql:8.4");
            withDatabaseName("moneytransfert");
        }

        @Override
        public String getDriverClassName() {
            return "org.mariadb.jdbc.Driver";
        }

        @Override
        public String getJdbcUrl() {
            return "jdbc:mariadb://"
                    + getHost()
                    + ":"
                    + getMappedPort(3306)
                    + "/"
                    + getDatabaseName();
        }
    }

    static final LegacyMysql MYSQL = new LegacyMysql();
    static Path publicDir;

    @Autowired LegacyMigration migration;
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    @Autowired SecretCipher cipher;
    @Autowired ObjectStorage storage;
    @Autowired WalletService wallets;
    @Autowired LedgerService ledger;
    @Autowired Clock clock;
    @Autowired JsonMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;

    @DynamicPropertySource
    static void legacy(DynamicPropertyRegistry registry) throws Exception {
        MYSQL.start();
        publicDir = Files.createTempDirectory("legacy-public");
        Files.createDirectories(publicDir.resolve("uploads"));
        Files.write(
                publicDir.resolve("uploads/face.jpg"),
                new byte[] {
                    (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0
                });
        Files.writeString(publicDir.resolve("uploads/faux.jpg"), "ceci n'est pas une image");
        registry.add("wtc.etl.source-url", () -> MYSQL.getJdbcUrl());
        registry.add("wtc.etl.source-user", MYSQL::getUsername);
        registry.add("wtc.etl.source-password", MYSQL::getPassword);
        registry.add("wtc.etl.public-dir", () -> publicDir.toString());
        registry.add("wtc.etl.report-file", () -> publicDir.resolve("report.json").toString());
        try (Connection c =
                DriverManager.getConnection(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            try (Statement off = c.createStatement()) {
                off.execute(
                        "SET FOREIGN_KEY_CHECKS = 0"); // le DDL est en ordre alphabétique, avec des
                // clés étrangères croisées
            }
            ScriptUtils.executeSqlScript(c, new ClassPathResource("legacy/schema.sql"));
            seedLegacy(c);
        }
    }

    @AfterAll
    static void stop() {
        MYSQL.stop();
    }

    private static void seedLegacy(Connection c) throws Exception {
        String hash =
                new BCryptPasswordEncoder().encode(PASSWORD).replaceFirst("^\\$2a\\$", "\\$2y\\$");
        try (Statement s = c.createStatement()) {
            s.execute(
                    "INSERT INTO zone (id, libelle, code) VALUES (1, 'AFRIQUE CENTRALE', 'AC'), (2,"
                            + " 'AMERIQUE', 'AM'), (3, 'GHANA', 'GH')");
            s.execute(
                    "INSERT INTO country (id, zone_id, code, code_string, libelle, monaire,"
                        + " payer_code) VALUES (1, 1, '237', 'CM', 'CAMEROUN', 'XAF', '111'), (2,"
                        + " 2, '+1', 'CD', 'CANADA', 'CAD', NULL), (3, 3, '+233', 'GH', 'GHANA',"
                        + " 'GHS', NULL), (4, 3, '+233', 'GH', 'GHANA', 'GHS', NULL)");
            s.execute(
                    "INSERT INTO town (id, libelle, code, country_id) VALUES (1, 'Douala', 'DLA',"
                            + " 1), (2, 'Douala', 'DLA', 1)");
            s.execute(
                    "INSERT INTO grilletarifaire (id, tranche_a, tranche_b, frais, zone_id) VALUES"
                            + " (1, 0, 1000000, 5, 1), (2, 500000, 2000000, 4, 1)");
            s.execute(
                    "INSERT INTO user (id, email, name, lastname, roles, password, phone) VALUES"
                            + " (1, 'Admin@Example.com', 'Ada', 'Min', '[\"ROLE_ADMIN\"]', '"
                            + hash
                            + "', NULL),"
                            + " (2, 'client@example.com', 'Cli', 'Ent', '[[\"ROLE_CUSTOMER\"]]', '"
                            + hash
                            + "', '690000001'),"
                            + " (3, 'super@example.com', 'Su', 'Per', '[\"ROLE_SUPER\"]', '"
                            + hash
                            + "', 'image'),"
                            + " (4, 'pas-un-email', 'X', 'Y', '[\"ROLE_USER\"]', '"
                            + hash
                            + "', NULL)");
            s.execute(
                    "INSERT INTO customer (id, firstname, lastname, email, isverify, compte_id,"
                        + " phone, country_id, customeralt, typeidentification, numeropiece,"
                        + " balance, date_of_birth, type, piecefacefile, expireddatepiece) VALUES"
                        + " (10, 'Cli', 'Ent', 'client@example.com', 1, 2, '690000001', 1, NULL,"
                        + " 'CNI', 'AB123456789', 1000, '12/05/1990', 'P', NULL, NULL), (11,"
                        + " 'Sender', 'Alt', NULL, 0, NULL, '690000002', 1, 10, 'passport',"
                        + " 'PP998877', 0, 'n/a', 'P', NULL, NULL), (12, 'Docs', 'Only', NULL, 0,"
                        + " NULL, NULL, 1, NULL, 'CNI', NULL, 0, '1985-01-01', 'P',"
                        + " 'uploads/face.jpg', NULL), (13, 'Neg', 'Ative', NULL, 0, NULL, NULL, 1,"
                        + " NULL, NULL, NULL, -999, NULL, 'P', 'uploads/faux.jpg', NULL)");
            s.execute(
                    "INSERT INTO contactcustomer (id, firstname, lastname, phone, bankname,"
                        + " bankaccountnumber, customer_id, country_iso_code, type) VALUES (20,"
                        + " 'Ben', 'Banque', '690000010', 'Banque X', 'CM21 0000 1234 5678', 10,"
                        + " 'CM', 'P'), (21, 'Ben', 'Mobile', '+237690000011', NULL, NULL, 10,"
                        + " NULL, 'P'), (22, NULL, NULL, NULL, NULL, NULL, 10, NULL, 'P')");
            s.execute(
                    "INSERT INTO transaction (id, customer_id, beneficiare_id, montant, fraisenvoi,"
                        + " montanttotal, datetransaction, numerotransaction, typetransaction,"
                        + " country_id, status, isdelete, partenairenumero, operateur,"
                        + " business_type) VALUES (100, 10, 20, 10000, 500, 10500, '2024-03-10"
                        + " 10:00:00', 'WTC-OLD-100', 'bancaire', 1, 'effectue', 0, 'P-100', NULL,"
                        + " 'P2P'), (101, 11, 21, 5000, 250, 5250, '2024-03-11 10:00:00',"
                        + " 'WTC-OLD-101', 'mobilemoney', 1, 'en validation', 0, NULL, 'MTN',"
                        + " 'P2P'), (102, 10, 20, 2000, 100, 2100, '2024-03-12 10:00:00',"
                        + " 'WTC-OLD-102', 'bank', 1, 'echoue', 1, NULL, NULL, NULL), (103, 10,"
                        + " NULL, 2000, 100, 2100, '2024-03-12 11:00:00', 'WTC-OLD-103', 'bank', 1,"
                        + " 'effectue', 0, NULL, NULL, 'P2P'), (104, 10, 21, 3000, 150, 3150,"
                        + " '2024-04-01 10:00:00', 'WTC-OLD-104', 'mobilemoney', 1, 'validation',"
                        + " 0, 'P-100', 'OM', 'P2P')");
            s.execute(
                    "INSERT INTO balance_customer (id, country_id, customer_id, solde) VALUES (1,"
                            + " 1, 10, 2500.5), (2, 2, 10, 12.345)");
            s.execute(
                    "INSERT INTO prefunding (id, country_id, customer_id, amount, mode,"
                            + " dateprefund, status) VALUES (1, 1, 10, 13000, 'STRIPE', '2024-01-01"
                            + " 00:00:00', 'PENDING')");
            s.execute(
                    "INSERT INTO web_token (id, token, amount, country_id, used, status,"
                            + " expires_at, created_at) VALUES (1, 'tok-legacy-1', 20000, 1, 0,"
                            + " 'PENDING', '2999-01-01 00:00:00', '2024-01-01 00:00:00')");
        }
    }

    private EtlProperties props(boolean dryRun) {
        return new EtlProperties(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword(),
                dryRun,
                publicDir,
                LocalDate.now(),
                false,
                publicDir.resolve("report-" + dryRun + ".json"));
    }

    private LegacyMigration with(EtlProperties props) {
        return new LegacyMigration(
                props, jdbc, txManager, dataSource, cipher, storage, wallets, ledger, clock, json);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Test
    @Order(1)
    void aDryRunReportsEverythingAndWritesNothing() {
        EtlResult result = with(props(true)).run();
        assertThat(result.controlsPassed()).as(result.failedControls().toString()).isTrue();
        assertThat(result.counters().get("transfers.migrated")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM users")).isZero();
        assertThat(count("SELECT count(*) FROM transfers")).isZero();
        assertThat(count("SELECT count(*) FROM countries")).isZero();
        assertThat(Files.exists(publicDir.resolve("report-true.json"))).isTrue();
    }

    @Test
    @Order(2)
    void theRealRunMigratesAndReconciles() {
        EtlResult result = migration.run();
        assertThat(result.controlsPassed()).as(result.failedControls().toString()).isTrue();

        // référentiels : codes ISO corrigés (CANADA portait « CD »), doublon fusionné, tranche en
        // chevauchement rejetée
        assertThat(
                        jdbc.sql("SELECT string_agg(code, ',' ORDER BY code) FROM countries")
                                .query(String.class)
                                .single())
                .isEqualTo("CA,CM,GH");
        assertThat(count("SELECT count(*) FROM fee_schedules")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM cities")).isEqualTo(1);

        // comptes : e-mail invalide rejeté, ROLE_SUPER rétrogradé en ADMIN, téléphone « image »
        // écarté
        assertThat(count("SELECT count(*) FROM users WHERE legacy_id IS NOT NULL")).isEqualTo(3);
        assertThat(
                        jdbc.sql(
                                        "SELECT string_agg(r.role_code, ',') FROM users u JOIN"
                                            + " user_roles r ON r.user_id = u.id WHERE u.legacy_id"
                                            + " = 3")
                                .query(String.class)
                                .single())
                .isEqualTo("ADMIN");
        assertThat(
                        jdbc.sql("SELECT phone_e164 FROM users WHERE legacy_id = 2")
                                .query(String.class)
                                .single())
                .isEqualTo("+237690000001");
        assertThat(
                        jdbc.sql("SELECT phone_e164 FROM users WHERE legacy_id = 3")
                                .query(String.class)
                                .optional())
                .isEmpty();

        // transferts : 4 migrés, 1 rejeté (sans bénéficiaire), statuts traduits, aucune écriture de
        // grand livre
        assertThat(count("SELECT count(*) FROM transfers WHERE legacy_id IS NOT NULL"))
                .isEqualTo(4);
        assertThat(
                        jdbc.sql(
                                        "SELECT string_agg(legacy_id || '=' || status, ',' ORDER BY"
                                                + " legacy_id) FROM transfers")
                                .query(String.class)
                                .single())
                .isEqualTo("100=COMPLETED,101=CANCELLED,102=FAILED,104=PROCESSING");
        assertThat(
                        count(
                                "SELECT count(*) FROM transfers WHERE debit_ledger_transaction_id"
                                        + " IS NOT NULL"))
                .isZero();
        assertThat(
                        count(
                                "SELECT count(*) FROM transfers WHERE legacy_id = 102 AND hidden_at"
                                        + " IS NOT NULL"))
                .isEqualTo(1);
        assertThat(
                        jdbc.sql("SELECT provider_reference FROM transfers WHERE legacy_id = 104")
                                .query(String.class)
                                .optional())
                .as("référence fournisseur en double : non reprise")
                .isEmpty();

        // soldes : 1000 XAF + 2500,5 XAF (arrondi à l'unité de la devise), 12,35 CAD ; négatif
        // ramené à zéro ; grand livre cohérent
        assertThat(
                        jdbc.sql(
                                        "SELECT balance FROM wallets w JOIN customers c ON c.id ="
                                                + " w.customer_id WHERE c.legacy_id = 10 AND"
                                                + " currency_code = 'XAF'")
                                .query(java.math.BigDecimal.class)
                                .single())
                .isEqualByComparingTo("3501");
        assertThat(
                        jdbc.sql(
                                        "SELECT balance FROM wallets w JOIN customers c ON c.id ="
                                                + " w.customer_id WHERE c.legacy_id = 10 AND"
                                                + " currency_code = 'CAD'")
                                .query(java.math.BigDecimal.class)
                                .single())
                .isEqualByComparingTo("12.35");
        assertThat(
                        jdbc.sql(
                                        "SELECT balance FROM wallets w JOIN customers c ON c.id ="
                                                + " w.customer_id WHERE c.legacy_id = 13")
                                .query(java.math.BigDecimal.class)
                                .single())
                .isEqualByComparingTo("0");
        assertThat(count("SELECT count(*) FROM wallet_balance_discrepancies")).isZero();

        // KYC : vérifié sans fichier -> pièce de reprise ; fichier JPEG valide migré ; faux fichier
        // refusé
        assertThat(
                        jdbc.sql("SELECT kyc_status FROM customers WHERE legacy_id = 10")
                                .query(String.class)
                                .single())
                .isEqualTo("VERIFIED");
        assertThat(
                        count(
                                "SELECT count(*) FROM kyc_documents d JOIN customers c ON c.id ="
                                    + " d.customer_id WHERE c.legacy_id = 10 AND d.storage_key LIKE"
                                    + " 'legacy/no-file/%'"))
                .isEqualTo(1);
        assertThat(
                        jdbc.sql("SELECT kyc_status FROM customers WHERE legacy_id = 12")
                                .query(String.class)
                                .single())
                .isEqualTo("PENDING");
        assertThat(
                        count(
                                "SELECT count(*) FROM kyc_documents d JOIN customers c ON c.id ="
                                        + " d.customer_id WHERE c.legacy_id = 13"))
                .isZero();

        // pièces et comptes chiffrés : relisibles avec les contextes de l'API, jamais en clair
        assertThat(
                        jdbc.sql(
                                        "SELECT id_document_last4 FROM parties WHERE legacy_source"
                                                + " = 'customer' AND legacy_id = 11")
                                .query(String.class)
                                .single())
                .isEqualTo("8877");
        assertThat(
                        count(
                                "SELECT count(*) FROM payout_destinations WHERE position('1234' in"
                                        + " encode(account_number_enc, 'escape')) > 0"))
                .isZero();

        // le rapport ne contient aucun numéro de pièce ou de compte en clair
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM etl_report WHERE original::text LIKE"
                                            + " '%AB123456789%' OR original::text LIKE '%5678%'")
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(count("SELECT count(*) FROM etl_report WHERE severity = 'ERROR'"))
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    @Order(3)
    void migratedCustomersCanLogInWithTheirOldPasswordAndSeeTheirHistory() {
        Api api = new Api(mvc);
        Reply login =
                api.post(
                        "/api/auth/login",
                        map("email", "client@example.com", "password", PASSWORD));
        assertThat(login.status()).as(login.body()).isEqualTo(200);
        String token = login.text("accessToken");
        assertThat(
                        jdbc.sql("SELECT password_hash FROM users WHERE legacy_id = 2")
                                .query(String.class)
                                .single())
                .as("rehaché en Argon2id à la première connexion")
                .startsWith("$argon2");

        Reply list = api.get("/api/me/transfers", token);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.contractViolations("listTransfers", 200)).isEmpty();
        assertThat(list.json().get("content").size())
                .isEqualTo(3); // la transaction masquée (102) n'est pas listée
        String completed = null;
        for (var t : list.json().get("content")) {
            if ("COMPLETED".equals(t.get("status").asString())) {
                completed = t.get("id").asString();
            }
        }
        assertThat(completed).isNotNull();
        Reply detail = api.get("/api/me/transfers/" + completed, token);
        assertThat(detail.contractViolations("getTransfer", 200)).isEmpty();
        assertThat(detail.json().get("destination").get("accountNumberMasked").asString())
                .startsWith("****");
        Reply receipt = api.get("/api/me/transfers/" + completed + "/receipt", token);
        assertThat(receipt.status()).isEqualTo(200);
        assertThat(receipt.header("Content-Type")).startsWith("application/pdf");

        Reply wallet = api.get("/api/me/wallets", token);
        assertThat(wallet.status()).isEqualTo(200);
        assertThat(wallet.contractViolations("listWallets", 200)).isEmpty();
    }

    @Test
    @Order(4)
    void aSecondRunChangesNothingAndTheGuardRefusesALiveDatabase() {
        long transfers = count("SELECT count(*) FROM transfers");
        long entries = count("SELECT count(*) FROM ledger_entries");
        long balance =
                jdbc.sql("SELECT sum(balance)::bigint FROM wallets").query(Long.class).single();
        EtlResult again = migration.run();
        assertThat(again.controlsPassed()).as(again.failedControls().toString()).isTrue();
        assertThat(count("SELECT count(*) FROM transfers")).isEqualTo(transfers);
        assertThat(count("SELECT count(*) FROM ledger_entries"))
                .as("écritures d'ouverture idempotentes")
                .isEqualTo(entries);
        assertThat(jdbc.sql("SELECT sum(balance)::bigint FROM wallets").query(Long.class).single())
                .isEqualTo(balance);

        jdbc.sql("UPDATE transfers SET legacy_id = NULL WHERE legacy_id = 100").update();
        assertThatThrownBy(() -> migration.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mise en service");
        jdbc.sql("UPDATE transfers SET legacy_id = 100 WHERE reference = 'WTC-OLD-100'").update();
    }

    @AfterAll
    static void clean(
            @Autowired JdbcClient jdbc,
            @Autowired UserRepository users,
            @Autowired PasswordHasher hasher) {
        jdbc.sql("DELETE FROM etl_report").update();
        jdbc.sql(
                        "TRUNCATE transfer_status_history, transfers, top_ups, provider_party_refs"
                                + " CASCADE")
                .update();
        new IdentityFixtures(jdbc, users, hasher).cleanUp();
        jdbc.sql("DELETE FROM cities").update();
        jdbc.sql("DELETE FROM fee_schedules").update();
        jdbc.sql("DELETE FROM banks").update();
        jdbc.sql("DELETE FROM mobile_operators").update();
        jdbc.sql("UPDATE countries SET pricing_zone_id = NULL").update();
        jdbc.sql("DELETE FROM countries").update();
        jdbc.sql("DELETE FROM pricing_zones").update();
    }
}
