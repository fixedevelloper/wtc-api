package com.wtc.wallet;

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
import com.wtc.wallet.application.WalletService;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Liste des recharges du back-office : contrat, filtres, recherche et permission. */
class TopUpAdminIT extends AbstractIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;
    @Autowired TestClock clock;
    @Autowired WalletService wallets;

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

    private void legacyTopUp(String customerId, String reference, String status, long legacyId) {
        long wallet = wallets.ensureWallet(UUID.fromString(customerId), "XAF").id();
        jdbc.sql(
                        """
                        INSERT INTO top_ups (reference, customer_id, wallet_id, method, amount, currency_code, status, legacy_id)
                        SELECT :r, c.id, :w, 'LEGACY', 5000, 'XAF', :s, :l FROM customers c WHERE c.public_id = :c
                        """)
                .param("r", reference)
                .param("w", wallet)
                .param("s", status)
                .param("l", legacyId)
                .param("c", UUID.fromString(customerId))
                .update();
    }

    @Test
    void staffListsTopUpsWithFiltersAndTheContractIsRespected() {
        Api api = new Api(mvc);
        IdentityFixtures fx = new IdentityFixtures(jdbc, users, hasher);
        clock.reset();
        Session me = Sessions.customer(api, fx, "client");
        legacyTopUp(me.customerId(), "TU-LEGACY-1", "COMPLETED", 9001);
        legacyTopUp(me.customerId(), "TU-LEGACY-2", "FAILED", 9002);
        Session admin = Sessions.staff(api, fx, clock, Role.ADMIN);

        Reply all = api.get("/api/admin/top-ups", admin.accessToken());
        assertThat(all.status()).as(all.body()).isEqualTo(200);
        assertThat(all.contractViolations("adminListTopUps", 200)).isEmpty();
        assertThat(all.text("totalElements")).isEqualTo("2");

        Reply done = api.get("/api/admin/top-ups?status=COMPLETED", admin.accessToken());
        assertThat(done.text("totalElements")).isEqualTo("1");
        assertThat(done.body()).contains("TU-LEGACY-1").doesNotContain("TU-LEGACY-2");

        Reply found = api.get("/api/admin/top-ups?search=LEGACY-2", admin.accessToken());
        assertThat(found.text("totalElements")).isEqualTo("1");
    }

    @Test
    void supportCannotListTopUps() {
        Api api = new Api(mvc);
        clock.reset();
        Session support =
                Sessions.staff(api, new IdentityFixtures(jdbc, users, hasher), clock, Role.SUPPORT);
        assertThat(api.get("/api/admin/top-ups", support.accessToken()).status()).isEqualTo(403);
        assertThat(api.get("/api/admin/top-ups", null).status()).isEqualTo(401);
    }
}
