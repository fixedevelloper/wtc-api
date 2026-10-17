package com.wtc.backoffice;

import static com.wtc.support.Api.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.identity.IdentityFixtures;
import com.wtc.identity.IdentityFixtures.Catalog;
import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.identity.domain.Role;
import com.wtc.payment.application.PayoutGateway.PayoutService;
import com.wtc.payment.application.PayoutGateway.ProviderBank;
import com.wtc.payment.infrastructure.FakePayoutGateway;
import com.wtc.support.AbstractIT;
import com.wtc.support.Api;
import com.wtc.support.Api.Reply;
import com.wtc.support.Sessions;
import com.wtc.support.TestClock;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Import des codes payeur, banques et libellés depuis (le faux) Wace. */
class ReferenceSyncIT extends AbstractIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;
    @Autowired TestClock clock;
    @Autowired FakePayoutGateway fake;

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
        jdbc.sql("DELETE FROM reference_values").update();
        new IdentityFixtures(jdbc, users, hasher).cleanUp();
    }

    @BeforeEach
    void init() {
        api = new Api(mvc);
        fx = new IdentityFixtures(jdbc, users, hasher);
        clock.reset();
        fake.reset();
        jdbc.sql("DELETE FROM reference_values").update();
        jdbc.sql("UPDATE countries SET wace_bank_payer_code = NULL").update();
        jdbc.sql("UPDATE mobile_operators SET wace_payer_code = NULL").update();
        jdbc.sql("DELETE FROM banks WHERE wace_bank_id IS NOT NULL").update();
        // Pays récepteur de test : YY (opérateurs « Mobile YY » / « MYY » et « Mobile sans envoi »
        // / « MNP »).
        fake.offerServices(
                "YY",
                List.of(
                        new PayoutService("B", "BANKS DEPOSIT", "XOF"),
                        new PayoutService("MYY", "MOBILE YY", "XOF")),
                List.of(
                        new ProviderBank(1, "10001", "BICEC"),
                        new ProviderBank(5, "10005", "Afriland")));
    }

    @Test
    void importsPayerCodesBanksAndLabelsAndReportsWhatItCouldNotMatch() {
        String t = Sessions.staff(api, fx, clock, Role.ADMIN).accessToken();

        Reply reply = api.post("/api/admin/reference/sync", null, t);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.contractViolations("adminSyncReferenceData", 200)).isEmpty();
        assertThat(
                        jdbc.sql("SELECT wace_bank_payer_code FROM countries WHERE code = 'YY'")
                                .query(String.class)
                                .single())
                .isEqualTo("PC-YY-B");
        assertThat(
                        jdbc.sql("SELECT wace_payer_code FROM mobile_operators WHERE id = :id")
                                .param("id", catalog.operatorYy())
                                .query(String.class)
                                .single())
                .as("rapproché par code court")
                .isEqualTo("PC-YY-MYY");
        assertThat(
                        jdbc.sql("SELECT wace_payer_code FROM mobile_operators WHERE id = :id")
                                .param("id", catalog.operatorWithoutPayout())
                                .query(String.class)
                                .optional())
                .as("sans service correspondant : jamais deviné")
                .isEmpty();
        assertThat(reply.json().get("warnings").toString()).contains("Mobile sans envoi");
        assertThat(reply.json().get("banks").asInt()).isEqualTo(2);
        assertThat(
                        jdbc.sql(
                                        "SELECT string_agg(name || ':' || short_code, ',' ORDER BY"
                                            + " wace_bank_id) FROM banks WHERE country_code = 'YY'"
                                            + " AND wace_bank_id IS NOT NULL")
                                .query(String.class)
                                .single())
                .isEqualTo("BICEC:10001,Afriland:10005");
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM reference_values WHERE category ="
                                                + " 'RELATIONSHIP' AND party_type_pair = 'ALL'")
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM reference_values WHERE category ="
                                                + " 'SOURCE_OF_FUNDS'")
                                .query(Long.class)
                                .single())
                .as("2 origines × 4 types")
                .isEqualTo(8);
        assertThat(
                        jdbc.sql(
                                        "SELECT wace_code FROM reference_values WHERE category ="
                                                + " 'TRANSFER_PURPOSE' AND party_type_pair = 'P2P'")
                                .query(String.class)
                                .single())
                .isEqualTo("Family Maintainance");
        assertThat(
                        jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'REFERENCE_SYNCED'")
                                .query(Long.class)
                                .single())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void runningTheImportTwiceChangesNothingAndNeverReactivatesWhatWasSwitchedOff() {
        String t = Sessions.staff(api, fx, clock, Role.ADMIN).accessToken();
        api.post("/api/admin/reference/sync", null, t);
        jdbc.sql("UPDATE banks SET enabled = false WHERE short_code = '10001'").update();
        jdbc.sql("UPDATE reference_values SET enabled = false WHERE code = 'Brother'").update();
        long banksBefore = jdbc.sql("SELECT count(*) FROM banks").query(Long.class).single();

        assertThat(api.post("/api/admin/reference/sync", null, t).status()).isEqualTo(200);

        assertThat(jdbc.sql("SELECT count(*) FROM banks").query(Long.class).single())
                .as("pas de doublon")
                .isEqualTo(banksBefore);
        assertThat(
                        jdbc.sql("SELECT enabled FROM banks WHERE short_code = '10001'")
                                .query(Boolean.class)
                                .single())
                .as("une banque désactivée le reste")
                .isFalse();
        assertThat(
                        jdbc.sql("SELECT enabled FROM reference_values WHERE code = 'Brother'")
                                .query(Boolean.class)
                                .single())
                .isFalse();
    }

    @Test
    void anImportRenamesAKnownBankWithoutTouchingItsIdentity() {
        String t = Sessions.staff(api, fx, clock, Role.ADMIN).accessToken();
        api.post("/api/admin/reference/sync", null, t);
        long id =
                jdbc.sql("SELECT id FROM banks WHERE wace_bank_id = '5'")
                        .query(Long.class)
                        .single();

        fake.offerServices(
                "YY",
                List.of(new PayoutService("B", "BANKS DEPOSIT", "XOF")),
                List.of(new ProviderBank(5, "10005", "Afriland First Bank")));
        api.post("/api/admin/reference/sync", null, t);

        assertThat(
                        jdbc.sql("SELECT name FROM banks WHERE id = :id")
                                .param("id", id)
                                .query(String.class)
                                .single())
                .isEqualTo("Afriland First Bank");
    }

    @Test
    void onlyReferenceWritersMayImport() {
        String support = Sessions.staff(api, fx, clock, Role.SUPPORT).accessToken();
        String customer = Sessions.customer(api, fx, "client").accessToken();
        assertThat(api.post("/api/admin/reference/sync", null, support).status()).isEqualTo(403);
        assertThat(api.post("/api/admin/reference/sync", null, customer).status()).isEqualTo(403);
        assertThat(api.post("/api/admin/reference/sync", map(), null).status()).isEqualTo(401);
        assertThat(fake.countCalls("payoutServices"))
                .as("aucun appel au fournisseur sans droit")
                .isZero();
    }
}
