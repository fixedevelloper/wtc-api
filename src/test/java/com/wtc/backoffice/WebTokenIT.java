package com.wtc.backoffice;

import static com.wtc.support.Api.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.backoffice.application.WebTokenService;
import com.wtc.identity.IdentityFixtures;
import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.identity.domain.Role;
import com.wtc.support.AbstractIT;
import com.wtc.support.Api;
import com.wtc.support.Api.Reply;
import com.wtc.support.Sessions;
import com.wtc.support.Sessions.Session;
import com.wtc.support.TestClock;
import com.wtc.support.TestProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Jetons web du chatbot WhatsApp : clé de service, usage unique, expiration. */
class WebTokenIT extends AbstractIT {

    private static final String KEY = TestProperties.TEST_SERVICE_KEY;

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;
    @Autowired TestClock clock;
    @Autowired WebTokenService service;

    Api api;
    IdentityFixtures fx;
    Session admin;

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
        new IdentityFixtures(jdbc, users, hasher).cleanUp();
    }

    @BeforeEach
    void init() {
        api = new Api(mvc);
        fx = new IdentityFixtures(jdbc, users, hasher);
        clock.reset();
        jdbc.sql("DELETE FROM web_tokens").update();
        admin = Sessions.staff(api, fx, clock, Role.ADMIN);
    }

    private String create(String amount, int minutes) {
        Reply r =
                api.post(
                        "/api/admin/web-tokens",
                        map("amount", amount, "countryCode", "YY", "validForMinutes", minutes),
                        admin.accessToken());
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        assertThat(r.contractViolations("adminCreateWebToken", 201)).isEmpty();
        return r.text("token");
    }

    private Reply service(String method, String path, String key, Object body) throws Exception {
        MockHttpServletRequestBuilder request =
                MockMvcRequestBuilders.request(
                        org.springframework.http.HttpMethod.valueOf(method), path);
        if (key != null) {
            request.header("X-Service-Key", key);
        }
        if (body != null) {
            request.contentType("application/json")
                    .content(new tools.jackson.databind.json.JsonMapper().writeValueAsString(body));
        }
        return new Reply(mvc.perform(request).andReturn());
    }

    @Test
    void theServiceReadsThenConsumesATokenExactlyOnce() throws Exception {
        String token = create("25000", 60);
        assertThat(jdbc.sql("SELECT token_hash FROM web_tokens").query(String.class).single())
                .as("seule l'empreinte est stockée")
                .doesNotContain(token);

        Reply read = service("GET", "/api/web-tokens/" + token, KEY, null);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.contractViolations("inspectWebToken", 200)).isEmpty();
        assertThat(read.text("status")).isEqualTo("PENDING");
        assertThat(read.json().get("amount").get("amount").asString()).isEqualTo("25000");
        assertThat(read.json().get("amount").get("currency").asString()).isEqualTo("XOF");

        Reply used =
                service(
                        "POST",
                        "/api/web-tokens/" + token + "/consume",
                        KEY,
                        map("whatsappId", "24206000000"));
        assertThat(used.status()).as(used.body()).isEqualTo(204);
        Reply again =
                service(
                        "POST",
                        "/api/web-tokens/" + token + "/consume",
                        KEY,
                        map("whatsappId", "24206000000"));
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("WEB_TOKEN_UNAVAILABLE");
        assertThat(service("GET", "/api/web-tokens/" + token, KEY, null).text("status"))
                .isEqualTo("USED");
    }

    @Test
    void theServiceKeyIsRequiredAndStaffOrCustomersCannotUseIt() throws Exception {
        String token = create("100", 60);
        assertThat(service("GET", "/api/web-tokens/" + token, null, null).status()).isEqualTo(401);
        assertThat(service("GET", "/api/web-tokens/" + token, "mauvaise-cle", null).status())
                .isEqualTo(401);
        assertThat(api.get("/api/web-tokens/" + token, admin.accessToken()).status())
                .as("un jeton d'accès, même administrateur, n'est pas une clé de service")
                .isEqualTo(403);
        assertThat(service("GET", "/api/web-tokens/inconnu", KEY, null).status()).isEqualTo(404);
        assertThat(
                        service(
                                        "POST",
                                        "/api/web-tokens/" + token + "/consume",
                                        KEY,
                                        map("whatsappId", ""))
                                .status())
                .isIn(400, 422);
    }

    @Test
    void anExpiredTokenCannotBeConsumedAndTheTaskMarksIt() throws Exception {
        String token = create("100", 5);
        clock.advance(Duration.ofMinutes(6));
        Reply read = service("GET", "/api/web-tokens/" + token, KEY, null);
        assertThat(read.text("status")).as("expiré avant même la tâche").isEqualTo("EXPIRED");
        assertThat(
                        service(
                                        "POST",
                                        "/api/web-tokens/" + token + "/consume",
                                        KEY,
                                        map("whatsappId", "1"))
                                .status())
                .isEqualTo(409);
        jdbc.sql("UPDATE web_tokens SET expires_at = now() - interval '1 minute'").update();
        clock.advance(Duration.ofDays(1));
        assertThat(service.expireDue()).isEqualTo(1);
        assertThat(service.expireDue()).isZero();
        assertThat(jdbc.sql("SELECT status FROM web_tokens").query(String.class).single())
                .isEqualTo("EXPIRED");
    }

    @Test
    void tenSimultaneousConsumptionsSucceedOnce() throws Exception {
        String token = create("100", 60);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String wa = "wa-" + i;
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return service(
                                                    "POST",
                                                    "/api/web-tokens/" + token + "/consume",
                                                    KEY,
                                                    map("whatsappId", wa))
                                            .status();
                                }));
            }
            start.countDown();
            int ok = 0;
            int conflicts = 0;
            for (Future<Integer> f : results) {
                int status = f.get();
                if (status == 204) ok++;
                else if (status == 409) conflicts++;
            }
            assertThat(ok).isEqualTo(1);
            assertThat(conflicts).isEqualTo(9);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void adminsListAndValidateTokenCreation() {
        create("100", 60);
        create("200", 60);
        Reply list = api.get("/api/admin/web-tokens?sort=amount,asc", admin.accessToken());
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.contractViolations("adminListWebTokens", 200)).isEmpty();
        assertThat(list.json().get("content").size()).isEqualTo(2);
        assertThat(list.json().get("content").get(0).get("amount").get("amount").asString())
                .isEqualTo("100");
        assertThat(list.body()).as("le jeton n'est jamais relisible").doesNotContain("\"token\"");

        assertThat(
                        api.post(
                                        "/api/admin/web-tokens",
                                        map("amount", "0", "countryCode", "YY"),
                                        admin.accessToken())
                                .status())
                .isIn(400, 422);
        assertThat(
                        api.post(
                                        "/api/admin/web-tokens",
                                        map("amount", "10", "countryCode", "WW"),
                                        admin.accessToken())
                                .status())
                .as("pays qui ne reçoit pas")
                .isEqualTo(404);
        Session customer = Sessions.customer(api, fx, "client");
        assertThat(api.get("/api/admin/web-tokens", customer.accessToken()).status())
                .isEqualTo(403);
    }
}
