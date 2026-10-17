package com.wtc.identity;

import static com.wtc.support.Api.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.support.AbstractIT;
import com.wtc.support.Api;
import com.wtc.support.Api.Reply;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Plafonds de requêtes sur les routes publiques sensibles (énumération de comptes, bourrage
 * d'identifiants).
 */
@TestPropertySource(
        properties = {
            "wtc.rate-limits.login-per-email-per-minute=2",
            "wtc.rate-limits.register-per-ip-per-hour=2",
            "wtc.rate-limits.password-reset-per-email-per-hour=1"
        })
class RateLimitIT extends AbstractIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;

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

    @Test
    void repeatedLoginAttemptsOnOneAddressAreThrottledWithARetryDelay() {
        Api api = new Api(mvc);
        String email = "cible-" + ThreadLocalRandom.current().nextInt(1_000_000) + "@example.test";

        assertThat(api.post("/api/auth/login", map("email", email, "password", "faux")).status())
                .isEqualTo(401);
        assertThat(api.post("/api/auth/login", map("email", email, "password", "faux")).status())
                .isEqualTo(401);
        Reply throttled = api.post("/api/auth/login", map("email", email, "password", "faux"));

        assertThat(throttled.status()).isEqualTo(429);
        assertThat(throttled.code()).isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(throttled.header("Retry-After"))).isBetween(1L, 60L);
        assertThat(throttled.contractViolations("login", 429)).isEmpty();
        assertThat(
                        api.post(
                                        "/api/auth/login",
                                        map("email", "autre-" + email, "password", "faux"))
                                .status())
                .as("une autre adresse e-mail n'est pas touchée")
                .isEqualTo(401);
    }

    @Test
    void registrationsFromOneAddressAreCappedPerHour() {
        Api api = new Api(mvc);
        int created = 0;
        Reply last = null;
        for (int i = 0; i < 4; i++) {
            String phone = "+2420" + (1_000_000 + ThreadLocalRandom.current().nextInt(8_999_999));
            last =
                    api.post(
                            "/api/auth/register",
                            map(
                                    "email",
                                    "limite-"
                                            + i
                                            + "-"
                                            + ThreadLocalRandom.current().nextInt(1_000_000)
                                            + "@example.test",
                                    "password",
                                    IdentityFixtures.PASSWORD,
                                    "firstName",
                                    "A",
                                    "lastName",
                                    "B",
                                    "phone",
                                    phone,
                                    "countryCode",
                                    "ZZ"));
            if (last.status() == 201) {
                created++;
            }
        }
        assertThat(created).isEqualTo(2);
        assertThat(last.status()).isEqualTo(429);
    }

    @Test
    void passwordResetRequestsPerAccountAreCappedWithoutRevealingTheAccount() {
        Api api = new Api(mvc);
        String known = "reset-" + ThreadLocalRandom.current().nextInt(1_000_000) + "@example.test";
        assertThat(api.post("/api/auth/password-reset/request", map("email", known)).status())
                .isEqualTo(204);
        assertThat(api.post("/api/auth/password-reset/request", map("email", known)).status())
                .isEqualTo(429);
    }
}
