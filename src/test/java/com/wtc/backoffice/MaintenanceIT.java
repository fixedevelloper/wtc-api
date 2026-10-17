package com.wtc.backoffice;

import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.backoffice.application.Housekeeping;
import com.wtc.customer.application.KycExpiryService;
import com.wtc.identity.IdentityFixtures;
import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.notification.application.Channel;
import com.wtc.notification.application.EventBridge;
import com.wtc.notification.application.NotificationDispatcher;
import com.wtc.notification.application.OperationsWatcher;
import com.wtc.notification.infrastructure.FakeNotificationSender;
import com.wtc.support.AbstractIT;
import com.wtc.support.Api;
import com.wtc.support.Sessions;
import com.wtc.support.Sessions.Session;
import com.wtc.support.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Tâches d'entretien : expiration des pièces d'identité, purges, synthèse quotidienne. */
class MaintenanceIT extends AbstractIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;
    @Autowired TestClock clock;
    @Autowired KycExpiryService kyc;
    @Autowired Housekeeping housekeeping;
    @Autowired EventBridge bridge;
    @Autowired NotificationDispatcher dispatcher;
    @Autowired OperationsWatcher watcher;
    @Autowired List<FakeNotificationSender> channels;

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
        new IdentityFixtures(jdbc, users, hasher).cleanUp();
    }

    @BeforeEach
    void init() {
        api = new Api(mvc);
        fx = new IdentityFixtures(jdbc, users, hasher);
        clock.reset();
        channels.forEach(FakeNotificationSender::reset);
        jdbc.sql("TRUNCATE outbox_events, notification_outbox").update();
        jdbc.sql("DELETE FROM kyc_documents").update();
    }

    private FakeNotificationSender channel(Channel c) {
        return channels.stream().filter(f -> f.channel() == c).findFirst().orElseThrow();
    }

    private void pump() {
        jdbc.sql(
                        "UPDATE outbox_events SET next_attempt_at = now() - interval '1 hour'"
                                + " WHERE status = 'PENDING'")
                .update();
        bridge.bridgeDue();
        dispatcher.dispatchDue();
    }

    private void approvedDocument(Session customer, LocalDate expiresOn) {
        jdbc.sql("UPDATE customers SET kyc_status = 'VERIFIED' WHERE public_id = CAST(:id AS uuid)")
                .param("id", customer.customerId())
                .update();
        jdbc.sql(
                        """
                        INSERT INTO kyc_documents (customer_id, document_type, storage_key, content_type, size_bytes, sha256,
                                                   status, expires_on, reviewed_by, reviewed_at)
                        SELECT c.id, 'NATIONAL_ID', 'k/' || gen_random_uuid(), 'image/jpeg', 10, repeat('a', 64),
                               'APPROVED', :exp, c.user_id, now()
                        FROM customers c WHERE c.public_id = CAST(:id AS uuid)
                        """)
                .param("id", customer.customerId())
                .param("exp", expiresOn == null ? null : java.sql.Date.valueOf(expiresOn))
                .update();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    @Test
    void anExpiringDocumentIsRemindedOnceThenExpiredWhichUnverifiesTheCustomer() {
        Session soon = Sessions.customer(api, fx, "bientot");
        Session never = Sessions.customer(api, fx, "sans-echeance");
        Session far = Sessions.customer(api, fx, "loin");
        approvedDocument(soon, today().plusDays(10));
        approvedDocument(never, null);
        approvedDocument(far, today().plusDays(200));

        var first = kyc.run();
        assertThat(first.reminders()).as("un seul document dans les 30 jours").isEqualTo(1);
        assertThat(first.expiredDocuments()).isZero();
        assertThat(kyc.run().reminders()).as("jamais deux rappels").isZero();
        pump();
        assertThat(channel(Channel.EMAIL).sent())
                .singleElement()
                .satisfies(
                        m -> {
                            assertThat(m.recipient()).isEqualTo(soon.email());
                            assertThat(m.content().subject()).contains("arrive à expiration");
                            assertThat(m.content().text())
                                    .contains(today().plusDays(10).toString());
                        });

        clock.advance(Duration.ofDays(11));
        var second = kyc.run();
        assertThat(second.expiredDocuments()).isEqualTo(1);
        assertThat(second.customersUpdated()).isEqualTo(1);
        assertThat(kycStatus(soon)).isEqualTo("EXPIRED");
        assertThat(kycStatus(never)).as("sans échéance : intact").isEqualTo("VERIFIED");
        assertThat(kycStatus(far)).isEqualTo("VERIFIED");
        assertThat(kyc.run().expiredDocuments()).as("idempotent").isZero();
        pump();
        assertThat(channel(Channel.EMAIL).sent()).hasSize(2);
        assertThat(channel(Channel.EMAIL).sent().get(1).content().subject()).contains("a expiré");
    }

    private String kycStatus(Session customer) {
        return jdbc.sql("SELECT kyc_status FROM customers WHERE public_id = CAST(:id AS uuid)")
                .param("id", customer.customerId())
                .query(String.class)
                .single();
    }

    @Test
    void housekeepingPurgesOldTechnicalRowsOnly() {
        Session user = Sessions.customer(api, fx, "purge");
        long userId =
                jdbc.sql("SELECT id FROM users WHERE public_id = CAST(:u AS uuid)")
                        .param("u", user.userId())
                        .query(Long.class)
                        .single();
        // chaîne de rotation périmée depuis longtemps : doit disparaître malgré les références
        // croisées
        long oldA = refresh(userId, "-100 days", null);
        long oldB = refresh(userId, "-99 days", null);
        jdbc.sql("UPDATE refresh_tokens SET replaced_by = :b WHERE id = :a")
                .param("a", oldA)
                .param("b", oldB)
                .update();
        long recent = refresh(userId, "-1 days", null);
        jdbc.sql(
                        "INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type,"
                            + " payload, status, processed_at) VALUES ('X', 1, 't', '{}', 'DONE',"
                            + " now() - interval '90 days'), ('X', 2, 't', '{}', 'DONE', now()),"
                            + " ('X', 3, 't', '{}', 'FAILED', now() - interval '90 days')")
                .update();
        jdbc.sql(
                        "INSERT INTO notification_outbox (channel, recipient, template, payload,"
                            + " status, dedupe_key, created_at) VALUES ('EMAIL', 'a', 't', '{}',"
                            + " 'SENT', 'k1', now() - interval '100 days'), ('EMAIL', 'a', 't',"
                            + " '{}', 'FAILED', 'k2', now() - interval '100 days'), ('EMAIL', 'a',"
                            + " 't', '{}', 'SENT', 'k3', now())")
                .update();

        var purged = housekeeping.run();
        assertThat(purged.refreshTokens()).isEqualTo(2);
        assertThat(purged.events()).isEqualTo(1);
        assertThat(purged.notifications()).isEqualTo(1);
        assertThat(
                        jdbc.sql("SELECT count(*) FROM refresh_tokens WHERE id = :id")
                                .param("id", recent)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        assertThat(
                        jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = 'FAILED'")
                                .query(Long.class)
                                .single())
                .as("les échecs restent")
                .isEqualTo(1);
        assertThat(
                        jdbc.sql("SELECT count(*) FROM notification_outbox WHERE status = 'FAILED'")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        assertThat(housekeeping.run().refreshTokens()).isZero();
    }

    private long refresh(long userId, String expiresOffset, Long replacedBy) {
        return jdbc.sql(
                        "INSERT INTO refresh_tokens (user_id, family_id, token_hash, issued_at,"
                                + " expires_at, replaced_by) VALUES (:u, gen_random_uuid(),"
                                + " gen_random_uuid()::text, now() + interval '"
                                + expiresOffset
                                + "' - interval '1 day',"
                                + " now() + interval '"
                                + expiresOffset
                                + "', :r) RETURNING id")
                .param("u", userId)
                .param("r", replacedBy)
                .query(Long.class)
                .single();
    }

    @Test
    void theDailyReportGoesOutOnceAfterSevenUtc() {
        Instant base = clock.instant();
        Instant sixAm =
                base.atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .plusDays(1)
                        .atTime(6, 0)
                        .toInstant(ZoneOffset.UTC);
        clock.advance(Duration.between(base, sixAm));
        assertThat(watcher.dailyReport()).as("trop tôt").isFalse();
        clock.advance(Duration.ofHours(2));
        assertThat(watcher.dailyReport()).isTrue();
        assertThat(watcher.dailyReport()).as("déjà envoyée").isFalse();
        dispatcher.dispatchDue();
        assertThat(channel(Channel.TELEGRAM).sent())
                .singleElement()
                .satisfies(
                        m ->
                                assertThat(m.content().text())
                                        .contains("Synthèse du")
                                        .contains("Nouveaux clients"));
    }
}
