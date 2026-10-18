package com.wtc.backoffice;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Tableau de bord : contrat, douze mois même sans activité, accès réservé au personnel. */
class DashboardIT extends AbstractIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;
    @Autowired TestClock clock;

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

    @Test
    void staffGetsTwelveMonthsAndTheContractIsRespected() {
        Api api = new Api(mvc);
        clock.reset();
        Session support =
                Sessions.staff(api, new IdentityFixtures(jdbc, users, hasher), clock, Role.SUPPORT);

        Reply r = api.get("/api/admin/dashboard", support.accessToken());

        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.contractViolations("adminDashboard", 200)).isEmpty();
        assertThat(r.text("currency")).isEqualTo("XAF");
    }

    @Test
    void anonymousAndBadCurrencyAreRefused() {
        Api api = new Api(mvc);
        assertThat(api.get("/api/admin/dashboard", null).status()).isEqualTo(401);
        clock.reset();
        Session admin =
                Sessions.staff(api, new IdentityFixtures(jdbc, users, hasher), clock, Role.ADMIN);
        assertThat(api.get("/api/admin/dashboard?currency=xx", admin.accessToken()).status())
                .isEqualTo(422);
    }
}
