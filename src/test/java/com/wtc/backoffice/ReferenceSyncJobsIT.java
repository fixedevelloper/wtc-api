package com.wtc.backoffice;

import static com.wtc.support.Api.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.identity.IdentityFixtures;
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

/** Import Wace en arrière-plan avec suivi d'avancement. */
class ReferenceSyncJobsIT extends AbstractIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;
    @Autowired TestClock clock;
    @Autowired FakePayoutGateway fake;

    Api api;
    IdentityFixtures fx;

    @BeforeAll
    static void seed(
            @Autowired JdbcClient jdbc,
            @Autowired UserRepository users,
            @Autowired PasswordHasher hasher) {
        new IdentityFixtures(jdbc, users, hasher).seedCatalog();
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
        fake.offerServices(
                "YY",
                List.of(
                        new PayoutService("B", "BANKS DEPOSIT", "XOF"),
                        new PayoutService("MYY", "MOBILE YY", "XOF")),
                List.of(
                        new ProviderBank(1, "10001", "BICEC"),
                        new ProviderBank(5, "10005", "Afriland")));
    }

    /** Attend la fin de l'import (fil d'arrière-plan) sans dépendre d'une durée fixe. */
    private Reply awaitFinished(String id, String token) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Reply r = api.get("/api/admin/reference/sync-jobs/" + id, token);
            assertThat(r.status()).isEqualTo(200);
            if (!"RUNNING".equals(r.json().get("status").asString())) {
                return r;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("L'import ne se termine pas");
    }

    @Test
    void aJobRunsInTheBackgroundAndReportsItsProgressUntilItSucceeds() throws Exception {
        String t = Sessions.staff(api, fx, clock, Role.ADMIN).accessToken();

        Reply started = api.post("/api/admin/reference/sync-jobs", map(), t);

        assertThat(started.status()).isEqualTo(202);
        assertThat(started.contractViolations("adminStartSyncJob", 202)).isEmpty();
        String id = started.json().get("id").asString();
        assertThat(started.json().get("totalSteps").asInt()).isGreaterThanOrEqualTo(2);

        Reply done = awaitFinished(id, t);

        assertThat(done.contractViolations("adminGetSyncJob", 200)).isEmpty();
        assertThat(done.json().get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(done.json().get("doneSteps").asInt())
                .isEqualTo(done.json().get("totalSteps").asInt());
        assertThat(done.json().get("banks").asInt()).isEqualTo(2);
        assertThat(done.json().get("labels").asString()).isEqualTo("OK");
        assertThat(done.json().get("referenceValues").asInt()).isPositive();
        assertThat(done.json().get("countries").toString()).contains("\"code\":\"YY\"");

        Reply list = api.get("/api/admin/reference/sync-jobs", t);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.contractViolations("adminListSyncJobs", 200)).isEmpty();
        assertThat(list.json().get(0).get("id").asString()).isEqualTo(id);
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM audit_log WHERE action ="
                                                + " 'REFERENCE_SYNCED'")
                                .query(Long.class)
                                .single())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void aJobCanTargetASingleCountryAndAnUnknownOneIsRefused() throws Exception {
        String t = Sessions.staff(api, fx, clock, Role.ADMIN).accessToken();

        Reply started = api.post("/api/admin/reference/sync-jobs", map("countryCode", "YY"), t);
        assertThat(started.status()).isEqualTo(202);
        assertThat(started.json().get("totalSteps").asInt()).isEqualTo(2);
        awaitFinished(started.json().get("id").asString(), t);

        Reply unknown = api.post("/api/admin/reference/sync-jobs", map("countryCode", "QQ"), t);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.contractViolations("adminStartSyncJob", 404)).isEmpty();
        assertThat(api.get("/api/admin/reference/sync-jobs/inconnu", t).status()).isEqualTo(404);
    }

    @Test
    void theOverviewShowsTheProviderAndWhatEachCountryStillLacks() throws Exception {
        String t = Sessions.staff(api, fx, clock, Role.ADMIN).accessToken();

        Reply before = api.get("/api/admin/reference/sync-overview", t);
        assertThat(before.status()).isEqualTo(200);
        assertThat(before.contractViolations("adminSyncOverview", 200)).isEmpty();
        assertThat(before.json().get("provider").get("name").asString()).isEqualTo("fake");
        assertThat(before.json().get("countries").toString())
                .contains("\"code\":\"YY\"", "\"bankPayerCode\":false");

        awaitFinished(
                api.post("/api/admin/reference/sync-jobs", map("countryCode", "YY"), t)
                        .json()
                        .get("id")
                        .asString(),
                t);

        Reply after = api.get("/api/admin/reference/sync-overview", t);
        assertThat(after.json().get("countries").toString()).contains("\"bankPayerCode\":true");
        assertThat(after.json().get("lastSync").get("at").asString()).isNotBlank();
    }

    @Test
    void onlyReferenceWritersMayUseIt() {
        String support = Sessions.staff(api, fx, clock, Role.SUPPORT).accessToken();
        String customer = Sessions.customer(api, fx, "client").accessToken();

        for (String token : new String[] {support, customer}) {
            assertThat(api.post("/api/admin/reference/sync-jobs", map(), token).status())
                    .isEqualTo(403);
            assertThat(api.get("/api/admin/reference/sync-jobs", token).status()).isEqualTo(403);
            assertThat(api.get("/api/admin/reference/sync-overview", token).status())
                    .isEqualTo(403);
        }
        assertThat(api.get("/api/admin/reference/sync-jobs", null).status()).isEqualTo(401);
        assertThat(fake.countCalls("payoutServices")).isZero();
    }
}
