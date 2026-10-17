package com.wtc.identity;

import static com.wtc.support.Api.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.SignedJWT;
import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.identity.domain.Role;
import com.wtc.identity.domain.Totp;
import com.wtc.shared.security.SecretCipher;
import com.wtc.support.AbstractIT;
import com.wtc.support.Api;
import com.wtc.support.Api.Reply;
import com.wtc.support.TestClock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Second facteur TOTP et gestion des comptes du personnel. */
class MfaAndStaffIT extends AbstractIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;
    @Autowired TestClock clock;
    @Autowired SecretCipher cipher;
    @Autowired JsonMapper json;

    Api api;
    IdentityFixtures fx;

    /**
     * Compte du personnel entièrement ouvert : jeton complet et secret TOTP pour les connexions
     * suivantes.
     */
    record Staff(
            String email,
            String accessToken,
            String refreshToken,
            String secret,
            String publicId) {}

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

    @BeforeEach
    void init() {
        api = new Api(mvc);
        fx = new IdentityFixtures(jdbc, users, hasher);
        clock.reset();
    }

    // ------------------------------------------------------------------ second facteur d'un client

    @Test
    void aCustomerActivatesTotpAndEveryLoginThenRequiresTheChallenge() {
        String email = fx.uniqueEmail("totp");
        String token = registerAndLogin(email);

        Reply enrollment = api.post("/api/me/mfa/enrollment", null, token);
        assertThat(enrollment.status()).isEqualTo(201);
        assertThat(enrollment.contractViolations("startMfaEnrollment", 201)).isEmpty();
        String secret = enrollment.text("secret");
        assertThat(enrollment.text("otpauthUri"))
                .startsWith("otpauth://totp/WTC:")
                .contains("secret=" + secret);
        assertThat(users.findByEmail(email).orElseThrow().mfaEnabled())
                .as("pas actif avant confirmation")
                .isFalse();

        assertThat(api.post("/api/me/mfa/confirmation", map("code", "000000"), token).code())
                .isEqualTo("MFA_CODE_REJECTED");
        assertThat(api.post("/api/me/mfa/confirmation", map("code", code(secret)), token).status())
                .isEqualTo(204);
        assertThat(api.post("/api/me/mfa/enrollment", null, token).code())
                .isEqualTo("MFA_ALREADY_ENABLED");
        assertThat(
                        jdbc.sql("SELECT mfa_secret_encrypted FROM users WHERE lower(email) = :e")
                                .param("e", email)
                                .query(byte[].class)
                                .single())
                .as("le secret est chiffré au repos")
                .satisfies(
                        bytes ->
                                assertThat(
                                                new String(
                                                        bytes,
                                                        java.nio.charset.StandardCharsets
                                                                .ISO_8859_1))
                                        .doesNotContain(secret));

        Reply challenge = login(email, IdentityFixtures.PASSWORD);
        assertThat(challenge.status()).isEqualTo(200);
        assertThat(challenge.contractViolations("login", 200)).isEmpty();
        assertThat(challenge.json().get("mfaRequired").asBoolean()).isTrue();
        assertThat(challenge.text("accessToken"))
                .as("aucun jeton avant le second facteur")
                .isNull();

        // Le code déjà utilisé pour confirmer ne sert plus (anti-rejeu) ; le suivant est accepté.
        String mfaToken = challenge.text("mfaToken");
        assertThat(
                        api.post(
                                        "/api/auth/mfa/verify",
                                        map("mfaToken", mfaToken, "code", code(secret)))
                                .code())
                .isEqualTo("INVALID_MFA_CODE");
        clock.advance(Duration.ofSeconds(30));
        Reply session =
                api.post("/api/auth/mfa/verify", map("mfaToken", mfaToken, "code", code(secret)));
        assertThat(session.status()).isEqualTo(200);
        assertThat(session.contractViolations("verifyMfa", 200)).isEmpty();
        assertThat(session.json().get("user").get("mfaEnabled").asBoolean()).isTrue();

        // Le défi est à usage unique, même avec un code encore valide.
        clock.advance(Duration.ofSeconds(30));
        assertThat(
                        api.post(
                                        "/api/auth/mfa/verify",
                                        map("mfaToken", mfaToken, "code", code(secret)))
                                .status())
                .isEqualTo(401);
    }

    @Test
    void wrongSecondFactorCodesAreAuditedAndLockTheAccount() {
        String email = fx.uniqueEmail("brute");
        String token = registerAndLogin(email);
        String secret = api.post("/api/me/mfa/enrollment", null, token).text("secret");
        api.post("/api/me/mfa/confirmation", map("code", code(secret)), token);

        for (int i = 0; i < 3; i++) {
            String mfaToken = login(email, IdentityFixtures.PASSWORD).text("mfaToken");
            assertThat(
                            api.post(
                                            "/api/auth/mfa/verify",
                                            map("mfaToken", mfaToken, "code", "123456"))
                                    .status())
                    .isEqualTo(401);
        }
        assertThat(login(email, IdentityFixtures.PASSWORD).status())
                .as("compte verrouillé")
                .isEqualTo(429);
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM login_audit WHERE"
                                                + " lower(email_attempted) = :e AND outcome ="
                                                + " 'MFA_FAILED'")
                                .param("e", email)
                                .query(Long.class)
                                .single())
                .isEqualTo(3);
    }

    @Test
    void anUnknownChallengeIsRefused() {
        Reply reply =
                api.post("/api/auth/mfa/verify", map("mfaToken", "inconnu", "code", "123456"));
        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.code()).isEqualTo("INVALID_MFA_CODE");
    }

    @Test
    void aCustomerCanDisableTheSecondFactorButStaffCannot() {
        String email = fx.uniqueEmail("desactive");
        String token = registerAndLogin(email);
        String secret = api.post("/api/me/mfa/enrollment", null, token).text("secret");
        api.post("/api/me/mfa/confirmation", map("code", code(secret)), token);

        clock.advance(Duration.ofSeconds(30));
        assertThat(api.delete("/api/me/mfa", map("code", "000000"), token).code())
                .isEqualTo("MFA_CODE_REJECTED");
        assertThat(api.delete("/api/me/mfa", map("code", code(secret)), token).status())
                .isEqualTo(204);
        assertThat(login(email, IdentityFixtures.PASSWORD).text("accessToken"))
                .as("plus de défi")
                .isNotBlank();

        Staff admin = openStaff(Role.SUPER_ADMIN);
        clock.advance(Duration.ofSeconds(30));
        Reply refused =
                api.delete("/api/me/mfa", map("code", code(admin.secret())), admin.accessToken());
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.code()).isEqualTo("MFA_REQUIRED_FOR_STAFF");
    }

    // ------------------------------------------------------------------ personnel

    @Test
    void staffWithoutSecondFactorOnlyGetsATokenToEnrollIt() throws Exception {
        String email = fx.uniqueEmail("nouveau-staff");
        fx.createUser(email, Set.of(Role.SUPER_ADMIN));

        Reply first = login(email, IdentityFixtures.PASSWORD);
        String token = first.text("accessToken");
        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.getJWTClaimsSet().getBooleanClaim("enrollment_only")).isTrue();
        assertThat(jwt.getJWTClaimsSet().getStringListClaim("roles")).isEmpty();
        assertThat(jwt.getJWTClaimsSet().getStringListClaim("permissions")).isEmpty();

        assertThat(api.get("/api/admin/users", token).status())
                .as("aucun droit tant que la 2FA n'est pas active")
                .isEqualTo(403);
        assertThat(api.post("/api/me/mfa/enrollment", null, token).status()).isEqualTo(201);

        Staff open = openStaff(Role.SUPER_ADMIN);
        Reply users = api.get("/api/admin/users", open.accessToken());
        assertThat(users.status()).isEqualTo(200);
        assertThat(users.contractViolations("adminListUsers", 200)).isEmpty();
    }

    @Test
    void customersCannotReachTheAdministration() {
        String token = registerAndLogin(fx.uniqueEmail("curieux"));
        assertThat(api.get("/api/admin/users", token).status()).isEqualTo(403);
        assertThat(api.get("/api/admin/audit-logs", token).status()).isEqualTo(403);
    }

    @Test
    void aSuperAdminInvitesStaffWhoThenChooseTheirOwnPassword() throws Exception {
        Staff admin = openStaff(Role.SUPER_ADMIN);
        String email = fx.uniqueEmail("support");

        Reply created =
                api.post(
                        "/api/admin/users",
                        map(
                                "email",
                                email,
                                "displayName",
                                "Agent Support",
                                "roles",
                                List.of("SUPPORT")),
                        admin.accessToken());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.contractViolations("adminCreateUser", 201)).isEmpty();
        assertThat(created.json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(login(email, IdentityFixtures.PASSWORD).status())
                .as("aucun mot de passe connu avant l'invitation")
                .isEqualTo(401);

        String invitation = outboxToken("identity.staff-invited", email);
        String chosen = "mot de passe choisi par l'agent";
        assertThat(
                        api.post(
                                        "/api/auth/password-reset/confirm",
                                        map("token", invitation, "newPassword", chosen))
                                .status())
                .isEqualTo(204);
        Reply firstLogin = login(email, chosen);
        assertThat(firstLogin.status()).isEqualTo(200);
        assertThat(
                        SignedJWT.parse(firstLogin.text("accessToken"))
                                .getJWTClaimsSet()
                                .getBooleanClaim("enrollment_only"))
                .isTrue();

        assertThat(
                        api.post(
                                        "/api/admin/users",
                                        map(
                                                "email",
                                                email.toUpperCase(),
                                                "displayName",
                                                "Doublon",
                                                "roles",
                                                List.of("SUPPORT")),
                                        admin.accessToken())
                                .code())
                .isEqualTo("EMAIL_ALREADY_REGISTERED");
        Reply onlyCustomerRole =
                api.post(
                        "/api/admin/users",
                        map(
                                "email",
                                fx.uniqueEmail("x"),
                                "displayName",
                                "X",
                                "roles",
                                List.of("CUSTOMER")),
                        admin.accessToken());
        assertThat(onlyCustomerRole.status()).isEqualTo(422);
        assertThat(onlyCustomerRole.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(auditActions(admin.publicId())).contains("STAFF_CREATED");
    }

    @Test
    void changingRolesOrStatusClosesTheirSessionsAndIsAudited() {
        Staff admin = openStaff(Role.SUPER_ADMIN);
        String email = fx.uniqueEmail("agent");
        long id = fx.createUser(email, Set.of(Role.SUPPORT));
        String targetId = users.findById(id).orElseThrow().publicId().toString();
        String refresh = login(email, IdentityFixtures.PASSWORD).text("refreshToken");

        Reply promoted =
                api.patch(
                        "/api/admin/users/" + targetId,
                        map("roles", List.of("ADMIN")),
                        admin.accessToken());
        assertThat(promoted.status()).isEqualTo(200);
        assertThat(promoted.contractViolations("adminUpdateUser", 200)).isEmpty();
        assertThat(promoted.json().get("roles").get(0).asString()).isEqualTo("ADMIN");
        assertThat(api.post("/api/auth/refresh", map("refreshToken", refresh)).status())
                .as("session fermée")
                .isEqualTo(401);

        assertThat(
                        api.patch(
                                        "/api/admin/users/" + targetId,
                                        map("status", "SUSPENDED"),
                                        admin.accessToken())
                                .status())
                .isEqualTo(200);
        assertThat(login(email, IdentityFixtures.PASSWORD).status()).isEqualTo(403);

        Reply logs =
                api.get(
                        "/api/admin/audit-logs?entityType=USER&entityId=" + targetId,
                        admin.accessToken());
        assertThat(logs.status()).isEqualTo(200);
        assertThat(logs.contractViolations("adminListAuditLogs", 200)).isEmpty();
        assertThat(logs.json().get("totalElements").asLong()).isEqualTo(2);
        var newest = logs.json().get("content").get(0);
        assertThat(newest.get("action").asString()).isEqualTo("STAFF_UPDATED");
        assertThat(newest.get("before").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(newest.get("after").get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(newest.get("actor").asString()).isEqualTo(admin.email());
        assertThat(logs.body())
                .as("l'audit ne contient aucun secret")
                .doesNotContain("password")
                .doesNotContain("$argon2");
    }

    @Test
    void guardRailsPreventLockoutAndPrivilegeEscalation() {
        Staff a = openStaff(Role.SUPER_ADMIN);
        Staff b = openStaff(Role.SUPER_ADMIN);

        Reply self =
                api.patch(
                        "/api/admin/users/" + a.publicId(),
                        map("displayName", "Moi"),
                        a.accessToken());
        assertThat(self.status()).isEqualTo(403);
        assertThat(self.code()).isEqualTo("SELF_MODIFICATION_FORBIDDEN");

        assertThat(api.get("/api/admin/users?size=100", a.accessToken()).status()).isEqualTo(200);
        // A suspend B ; B garde un jeton encore valide mais son compte est suspendu : il ne peut
        // plus agir.
        assertThat(
                        api.patch(
                                        "/api/admin/users/" + b.publicId(),
                                        map("status", "SUSPENDED"),
                                        a.accessToken())
                                .status())
                .isEqualTo(200);
        Reply suspendedActor =
                api.patch(
                        "/api/admin/users/" + a.publicId(),
                        map("status", "SUSPENDED"),
                        b.accessToken());
        assertThat(suspendedActor.status()).isEqualTo(403);
        assertThat(
                        users.findByPublicId(java.util.UUID.fromString(a.publicId()))
                                .orElseThrow()
                                .status()
                                .name())
                .isEqualTo("ACTIVE");

        Reply notStaff =
                api.patch(
                        "/api/admin/users/" + registerAndGetId(fx.uniqueEmail("client")),
                        map("status", "SUSPENDED"),
                        a.accessToken());
        assertThat(notStaff.status()).isEqualTo(404);
        assertThat(notStaff.code()).isEqualTo("USER_NOT_FOUND");

        Reply empty = api.patch("/api/admin/users/" + a.publicId(), map(), a.accessToken());
        assertThat(empty.status()).isIn(400, 422);
    }

    @Test
    void twoSuperAdminsSuspendingEachOtherAtOnceCannotLeaveTheSystemWithoutOne() throws Exception {
        Staff a = openStaff(Role.SUPER_ADMIN);
        Staff b = openStaff(Role.SUPER_ADMIN);
        // Tous les autres super-administrateurs de ce jeu de tests sont mis de côté : A et B sont
        // les deux seuls actifs.
        jdbc.sql(
                        "UPDATE users SET status = 'SUSPENDED' WHERE id IN (SELECT user_id FROM"
                            + " user_roles WHERE role_code = 'SUPER_ADMIN') AND lower(email) NOT IN"
                            + " (:a, :b)")
                .param("a", a.email())
                .param("b", b.email())
                .update();

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var f1 =
                    pool.submit(
                            () -> {
                                start.await();
                                return api.patch(
                                                "/api/admin/users/" + b.publicId(),
                                                map("status", "SUSPENDED"),
                                                a.accessToken())
                                        .status();
                            });
            var f2 =
                    pool.submit(
                            () -> {
                                start.await();
                                return api.patch(
                                                "/api/admin/users/" + a.publicId(),
                                                map("status", "SUSPENDED"),
                                                b.accessToken())
                                        .status();
                            });
            start.countDown();
            List<Integer> statuses = List.of(f1.get(), f2.get());
            assertThat(statuses).as("une seule suspension réussit").containsOnlyOnce(200);
            assertThat(statuses)
                    .filteredOn(code -> code != 200)
                    .allMatch(code -> code == 409 || code == 403);
        } finally {
            pool.shutdownNow();
        }
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM users u JOIN user_roles r ON"
                                            + " r.user_id = u.id WHERE r.role_code = 'SUPER_ADMIN'"
                                            + " AND u.status = 'ACTIVE'")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void onlyASuperAdminGrantsAdministrationRolesEvenWithUserManage() {
        jdbc.sql(
                        "INSERT INTO role_permissions (role_code, permission_code) VALUES ('ADMIN',"
                                + " 'USER_MANAGE')")
                .update();
        try {
            Staff admin = openStaff(Role.ADMIN);
            Reply escalate =
                    api.post(
                            "/api/admin/users",
                            map(
                                    "email",
                                    fx.uniqueEmail("pirate"),
                                    "displayName",
                                    "P",
                                    "roles",
                                    List.of("SUPER_ADMIN")),
                            admin.accessToken());
            assertThat(escalate.status()).isEqualTo(403);
            Reply modest =
                    api.post(
                            "/api/admin/users",
                            map(
                                    "email",
                                    fx.uniqueEmail("modeste"),
                                    "displayName",
                                    "M",
                                    "roles",
                                    List.of("SUPPORT")),
                            admin.accessToken());
            assertThat(modest.status()).isEqualTo(201);
        } finally {
            jdbc.sql(
                            "DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND"
                                    + " permission_code = 'USER_MANAGE'")
                    .update();
        }
    }

    @Test
    void resettingAnotherAccountsSecondFactorSendsThemBackToEnrollment() throws Exception {
        Staff admin = openStaff(Role.SUPER_ADMIN);
        Staff agent = openStaff(Role.SUPPORT);

        assertThat(
                        api.post(
                                        "/api/admin/users/" + agent.publicId() + "/mfa-reset",
                                        null,
                                        admin.accessToken())
                                .status())
                .isEqualTo(204);

        assertThat(
                        api.post("/api/auth/refresh", map("refreshToken", agent.refreshToken()))
                                .status())
                .isEqualTo(401);
        Reply again = login(agent.email(), IdentityFixtures.PASSWORD);
        assertThat(
                        SignedJWT.parse(again.text("accessToken"))
                                .getJWTClaimsSet()
                                .getBooleanClaim("enrollment_only"))
                .isTrue();
        assertThat(
                        api.post(
                                        "/api/admin/users/" + admin.publicId() + "/mfa-reset",
                                        null,
                                        admin.accessToken())
                                .code())
                .isEqualTo("SELF_MODIFICATION_FORBIDDEN");
    }

    @Test
    void permissionsDecideWhoReadsTheAuditTrails() {
        Staff support = openStaff(Role.SUPPORT); // SUPPORT n'a pas AUDIT_READ
        Staff admin = openStaff(Role.ADMIN);
        assertThat(api.get("/api/admin/login-audits", support.accessToken()).status())
                .isEqualTo(403);

        Reply logins =
                api.get("/api/admin/login-audits?size=5&sort=occurredAt,desc", admin.accessToken());
        assertThat(logins.status()).isEqualTo(200);
        assertThat(logins.contractViolations("adminListLoginAudits", 200)).isEmpty();
        assertThat(logins.json().get("content").size()).isBetween(1, 5);
        assertThat(
                        api.get("/api/admin/login-audits?sort=password,asc", admin.accessToken())
                                .status())
                .as("tri hors liste blanche")
                .isEqualTo(422);
        assertThat(api.get("/api/admin/login-audits?size=1000", admin.accessToken()).status())
                .isIn(400, 422);
    }

    // ------------------------------------------------------------------ utilitaires

    /**
     * Crée un compte du personnel, active sa 2FA et le connecte : retourne un jeton portant ses
     * vrais droits.
     */
    private Staff openStaff(Role role) {
        String email = fx.uniqueEmail(role.name().toLowerCase().replace('_', '-'));
        fx.createUser(email, Set.of(role));
        String enrollToken = login(email, IdentityFixtures.PASSWORD).text("accessToken");
        String secret = api.post("/api/me/mfa/enrollment", null, enrollToken).text("secret");
        assertThat(
                        api.post("/api/me/mfa/confirmation", map("code", code(secret)), enrollToken)
                                .status())
                .isEqualTo(204);

        String mfaToken = login(email, IdentityFixtures.PASSWORD).text("mfaToken");
        clock.advance(Duration.ofSeconds(30)); // le code de confirmation est déjà consommé
        Reply session =
                api.post("/api/auth/mfa/verify", map("mfaToken", mfaToken, "code", code(secret)));
        assertThat(session.status()).isEqualTo(200);
        return new Staff(
                email,
                session.text("accessToken"),
                session.text("refreshToken"),
                secret,
                session.json().get("user").get("id").asString());
    }

    private String code(String base32Secret) {
        return Totp.codeAt(
                Totp.fromBase32(base32Secret), Totp.stepAt(clock.instant().getEpochSecond()));
    }

    private String registerAndLogin(String email) {
        register(email);
        return login(email, IdentityFixtures.PASSWORD).text("accessToken");
    }

    private String registerAndGetId(String email) {
        return register(email).text("id");
    }

    private Reply register(String email) {
        String phone = "+2420" + (1_000_000 + ThreadLocalRandom.current().nextInt(8_999_999));
        return api.post(
                "/api/auth/register",
                map(
                        "email",
                        email,
                        "password",
                        IdentityFixtures.PASSWORD,
                        "firstName",
                        "Claire",
                        "lastName",
                        "Dupont",
                        "phone",
                        phone,
                        "countryCode",
                        "ZZ"));
    }

    private Reply login(String email, String password) {
        return api.post("/api/auth/login", map("email", email, "password", password));
    }

    private List<String> auditActions(String userPublicId) {
        return jdbc.sql(
                        "SELECT action FROM audit_log a JOIN users u ON u.id = a.actor_user_id"
                                + " WHERE u.public_id = CAST(:id AS uuid) ORDER BY a.id")
                .param("id", userPublicId)
                .query(String.class)
                .list();
    }

    private String outboxToken(String eventType, String email) throws Exception {
        String payload =
                jdbc.sql(
                                "SELECT payload::text FROM outbox_events WHERE event_type = :t AND"
                                        + " payload->>'email' = :e ORDER BY id DESC LIMIT 1")
                        .param("t", eventType)
                        .param("e", email)
                        .query(String.class)
                        .single();
        String encrypted = json.readTree(payload).get("tokenEncrypted").asString();
        return cipher.decrypt(Base64.getDecoder().decode(encrypted), "outbox:identity-token");
    }
}
