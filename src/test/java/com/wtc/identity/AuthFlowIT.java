package com.wtc.identity;

import static com.wtc.support.Api.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.SignedJWT;
import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.identity.domain.Role;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parcours d'authentification de bout en bout : HTTP → services → PostgreSQL, conformes au contrat.
 */
class AuthFlowIT extends AbstractIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;
    @Autowired TestClock clock;
    @Autowired SecretCipher cipher;
    @Autowired JsonMapper json;

    Api api;
    IdentityFixtures fx;

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

    // ------------------------------------------------------------------ inscription

    @Test
    void registrationCreatesAnActiveCustomerAndMatchesTheContract() {
        String email = fx.uniqueEmail("nouveau");
        Reply reply = register(email);

        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.contractViolations("register", 201)).isEmpty();
        assertThat(reply.json().get("roles").get(0).asString()).isEqualTo("CUSTOMER");
        assertThat(reply.json().get("customerId"))
                .as("la fiche client est créée avec le compte")
                .isNotNull();
        assertThat(reply.body())
                .as("jamais de secret dans la réponse")
                .doesNotContain("password")
                .doesNotContain("$argon2");
        assertThat(fx.passwordHashOf(email)).startsWith("$argon2id$");
    }

    @Test
    void registrationRejectsDuplicatesWeakPasswordsAndUnsupportedCountries() {
        String email = fx.uniqueEmail("doublon");
        assertThat(register(email).status()).isEqualTo(201);

        assertThat(register(email.toUpperCase()).code())
                .as("e-mail insensible à la casse")
                .isEqualTo("EMAIL_ALREADY_REGISTERED");

        Reply weak =
                api.post(
                        "/api/auth/register",
                        registration(fx.uniqueEmail("faible"), "motdepasse123", "ZZ"));
        assertThat(weak.status()).isEqualTo(422);
        assertThat(weak.code()).isEqualTo("WEAK_PASSWORD");
        assertThat(weak.json().get("errors").get(0).get("field").asString()).isEqualTo("password");

        Reply country =
                api.post(
                        "/api/auth/register",
                        registration(fx.uniqueEmail("pays"), IdentityFixtures.PASSWORD, "QQ"));
        assertThat(country.status()).isEqualTo(422);
        assertThat(country.code()).isEqualTo("VALIDATION_FAILED");

        Reply invalid =
                api.post("/api/auth/register", map("email", "pas-un-email", "password", "x"));
        assertThat(invalid.status()).isIn(400, 422);
        assertThat(invalid.code()).isIn("VALIDATION_FAILED", "MALFORMED_REQUEST");
    }

    // ------------------------------------------------------------------ connexion

    @Test
    void loginIssuesRs256TokensCarryingRolesAndPermissions() throws Exception {
        String email = fx.uniqueEmail("client");
        register(email);

        Reply reply = login(email, IdentityFixtures.PASSWORD);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.header("Cache-Control")).contains("no-store");
        assertThat(reply.contractViolations("login", 200)).isEmpty();
        SignedJWT jwt = SignedJWT.parse(reply.text("accessToken"));
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(jwt.getJWTClaimsSet().getStringListClaim("roles")).containsExactly("CUSTOMER");
        assertThat(jwt.getJWTClaimsSet().getSubject())
                .isEqualTo(reply.json().get("user").get("id").asString());
        assertThat(jwt.getJWTClaimsSet().getStringClaim("sid")).isNotBlank();
        assertThat(reply.json().get("expiresIn").asLong()).isEqualTo(900);
        assertThat(api.get("/api/me", reply.text("accessToken")).status())
                .as("le jeton émis est accepté par l'API")
                .isEqualTo(200);
    }

    @Test
    void anUnknownAccountAndAWrongPasswordAreIndistinguishable() {
        String email = fx.uniqueEmail("client");
        register(email);

        Reply wrongPassword = login(email, "un autre mot de passe");
        Reply unknown = login(fx.uniqueEmail("fantome"), "un autre mot de passe");

        assertThat(wrongPassword.status()).isEqualTo(401).isEqualTo(unknown.status());
        assertThat(wrongPassword.code()).isEqualTo("INVALID_CREDENTIALS").isEqualTo(unknown.code());
        assertThat(wrongPassword.text("detail")).isEqualTo(unknown.text("detail"));
    }

    @Test
    void theAccountLocksAfterRepeatedFailuresAndUnlocksLater() {
        String email = fx.uniqueEmail("verrou");
        register(email);

        for (int i = 0; i < 3; i++) {
            assertThat(login(email, "mauvais mot de passe").status()).isEqualTo(401);
        }
        Reply locked =
                login(
                        email,
                        IdentityFixtures
                                .PASSWORD); // même le bon mot de passe est refusé pendant le verrou
        assertThat(locked.status()).isEqualTo(429);
        assertThat(locked.code()).isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(locked.header("Retry-After"))).isBetween(1L, 900L);

        clock.advance(Duration.ofMinutes(16));
        assertThat(login(email, IdentityFixtures.PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void suspendedAccountsCannotLogIn() {
        String email = fx.uniqueEmail("suspendu");
        long id = fx.createUser(email, Set.of(Role.CUSTOMER));
        users.setStatus(id, com.wtc.identity.domain.UserStatus.SUSPENDED);

        Reply reply = login(email, IdentityFixtures.PASSWORD);

        assertThat(reply.status()).isEqualTo(403);
        assertThat(loginAuditOutcomes(email)).containsExactly("SUSPENDED");
    }

    @Test
    void everyLoginAttemptIsAudited() {
        String email = fx.uniqueEmail("audit");
        register(email);
        login(email, "mauvais mot de passe");
        login(email, IdentityFixtures.PASSWORD);

        assertThat(loginAuditOutcomes(email)).containsExactly("BAD_CREDENTIALS", "SUCCESS");
    }

    @Test
    void legacyBcryptHashesAreAcceptedThenUpgradedToArgon2id() {
        String email = fx.uniqueEmail("symfony");
        String bcrypt =
                new BCryptPasswordEncoder()
                        .encode(IdentityFixtures.PASSWORD)
                        .replaceFirst("^\\$2a\\$", "\\$2y\\$");
        fx.createLegacyBcryptUser(email, bcrypt);
        assertThat(fx.passwordHashOf(email)).startsWith("$2y$");

        assertThat(login(email, IdentityFixtures.PASSWORD).status()).isEqualTo(200);

        assertThat(fx.passwordHashOf(email)).startsWith("$argon2id$");
        assertThat(login(email, IdentityFixtures.PASSWORD).status())
                .as("le nouveau hachage fonctionne")
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ sessions

    @Test
    void refreshRotatesTheTokenAndAReplayRevokesTheWholeFamily() {
        String email = fx.uniqueEmail("rotation");
        register(email);
        Reply first = login(email, IdentityFixtures.PASSWORD);
        String refresh1 = first.text("refreshToken");

        Reply second = api.post("/api/auth/refresh", map("refreshToken", refresh1));
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.contractViolations("refreshToken", 200)).isEmpty();
        String refresh2 = second.text("refreshToken");
        assertThat(refresh2).isNotEqualTo(refresh1);

        // Rejeu de l'ancien jeton : refusé, et la famille entière est révoquée (le nouveau jeton
        // aussi).
        Reply replay = api.post("/api/auth/refresh", map("refreshToken", refresh1));
        assertThat(replay.status()).isEqualTo(401);
        assertThat(replay.code()).isEqualTo("REFRESH_TOKEN_REUSED");
        Reply afterReplay = api.post("/api/auth/refresh", map("refreshToken", refresh2));
        assertThat(afterReplay.status()).isEqualTo(401);
        assertThat(afterReplay.code()).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM audit_log WHERE action ="
                                            + " 'REFRESH_TOKEN_REUSE_DETECTED' AND actor_label ="
                                            + " :e")
                                .param("e", email)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void anUnknownOrExpiredRefreshTokenIsRejected() {
        assertThat(api.post("/api/auth/refresh", map("refreshToken", "inconnu")).code())
                .isEqualTo("INVALID_REFRESH_TOKEN");

        String email = fx.uniqueEmail("expire");
        register(email);
        String refresh = login(email, IdentityFixtures.PASSWORD).text("refreshToken");
        clock.advance(Duration.ofDays(15));
        assertThat(api.post("/api/auth/refresh", map("refreshToken", refresh)).code())
                .isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    void aSessionCannotBeRenewedBeyondItsMaximumAge() {
        String email = fx.uniqueEmail("plafond");
        register(email);
        String refresh = login(email, IdentityFixtures.PASSWORD).text("refreshToken");
        // Renouvellements réguliers (tous les 10 jours) : chaque jeton reste valide, mais la
        // session meurt à 90 jours.
        int renewals = 0;
        Reply reply;
        while (true) {
            clock.advance(Duration.ofDays(10));
            reply = api.post("/api/auth/refresh", map("refreshToken", refresh));
            if (reply.status() != 200) {
                break;
            }
            refresh = reply.text("refreshToken");
            renewals++;
            assertThat(renewals).as("la session doit s'arrêter").isLessThan(12);
        }
        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.code()).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(renewals).isBetween(7, 9);
    }

    @Test
    void logoutRevokesTheSession() {
        String email = fx.uniqueEmail("sortie");
        register(email);
        Reply session = login(email, IdentityFixtures.PASSWORD);

        assertThat(
                        api.post(
                                        "/api/auth/logout",
                                        map("refreshToken", session.text("refreshToken")),
                                        session.text("accessToken"))
                                .status())
                .isEqualTo(204);
        assertThat(
                        api.post(
                                        "/api/auth/refresh",
                                        map("refreshToken", session.text("refreshToken")))
                                .status())
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------ mot de passe

    @Test
    void passwordResetFlowWorksOnceAndClosesAllSessions() throws Exception {
        String email = fx.uniqueEmail("oubli");
        register(email);
        Reply session = login(email, IdentityFixtures.PASSWORD);

        assertThat(
                        api.post(
                                        "/api/auth/password-reset/request",
                                        map("email", fx.uniqueEmail("inconnu")))
                                .status())
                .as("même réponse pour un compte inconnu")
                .isEqualTo(204);
        assertThat(api.post("/api/auth/password-reset/request", map("email", email)).status())
                .isEqualTo(204);
        String token = resetTokenFor(email);

        Reply weak =
                api.post(
                        "/api/auth/password-reset/confirm",
                        map("token", token, "newPassword", "motdepasse123"));
        assertThat(weak.code()).isEqualTo("WEAK_PASSWORD");

        String newPassword = "nouveau mot de passe solide";
        assertThat(
                        api.post(
                                        "/api/auth/password-reset/confirm",
                                        map("token", token, "newPassword", newPassword))
                                .status())
                .as("le refus d'un mot de passe faible n'a pas brûlé le lien")
                .isEqualTo(204);

        assertThat(login(email, IdentityFixtures.PASSWORD).status()).isEqualTo(401);
        assertThat(login(email, newPassword).status()).isEqualTo(200);
        assertThat(
                        api.post(
                                        "/api/auth/refresh",
                                        map("refreshToken", session.text("refreshToken")))
                                .status())
                .as("les anciennes sessions sont fermées")
                .isEqualTo(401);
        Reply replay =
                api.post(
                        "/api/auth/password-reset/confirm",
                        map("token", token, "newPassword", "encore un autre mot de passe"));
        assertThat(replay.status()).isEqualTo(422);
        assertThat(replay.code()).isEqualTo("INVALID_RESET_TOKEN");
    }

    @Test
    void anExpiredResetLinkIsRefusedAndOnlyTheLatestLinkWorks() throws Exception {
        String email = fx.uniqueEmail("lien");
        register(email);
        api.post("/api/auth/password-reset/request", map("email", email));
        String old = resetTokenFor(email);
        api.post("/api/auth/password-reset/request", map("email", email));
        String latest = resetTokenFor(email);

        assertThat(
                        api.post(
                                        "/api/auth/password-reset/confirm",
                                        map(
                                                "token",
                                                old,
                                                "newPassword",
                                                "nouveau mot de passe solide"))
                                .code())
                .as("un nouveau lien invalide les précédents")
                .isEqualTo("INVALID_RESET_TOKEN");

        clock.advance(Duration.ofHours(2));
        assertThat(
                        api.post(
                                        "/api/auth/password-reset/confirm",
                                        map(
                                                "token",
                                                latest,
                                                "newPassword",
                                                "nouveau mot de passe solide"))
                                .code())
                .isEqualTo("INVALID_RESET_TOKEN");
    }

    @Test
    void resetTokensAreNeverStoredInClear() throws Exception {
        String email = fx.uniqueEmail("clair");
        register(email);
        api.post("/api/auth/password-reset/request", map("email", email));
        String token = resetTokenFor(email);

        assertThat(
                        jdbc.sql("SELECT count(*) FROM one_time_tokens WHERE token_hash = :t")
                                .param("t", token)
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(
                        jdbc.sql(
                                        "SELECT payload::text FROM outbox_events WHERE"
                                                + " payload->>'email' = :e")
                                .param("e", email)
                                .query(String.class)
                                .list())
                .allSatisfy(payload -> assertThat(payload).doesNotContain(token));
    }

    @Test
    void changingThePasswordKeepsTheCurrentSessionAndClosesTheOthers() {
        String email = fx.uniqueEmail("change");
        register(email);
        Reply here = login(email, IdentityFixtures.PASSWORD);
        Reply elsewhere = login(email, IdentityFixtures.PASSWORD);

        Reply wrong =
                api.put(
                        "/api/me/password",
                        map(
                                "currentPassword",
                                "faux",
                                "newPassword",
                                "nouveau mot de passe solide"),
                        here.text("accessToken"));
        assertThat(wrong.status()).isEqualTo(422);
        assertThat(wrong.code()).isEqualTo("INVALID_CURRENT_PASSWORD");

        Reply ok =
                api.put(
                        "/api/me/password",
                        map(
                                "currentPassword",
                                IdentityFixtures.PASSWORD,
                                "newPassword",
                                "nouveau mot de passe solide"),
                        here.text("accessToken"));
        assertThat(ok.status()).isEqualTo(204);

        assertThat(
                        api.post(
                                        "/api/auth/refresh",
                                        map("refreshToken", elsewhere.text("refreshToken")))
                                .status())
                .isEqualTo(401);
        assertThat(
                        api.post(
                                        "/api/auth/refresh",
                                        map("refreshToken", here.text("refreshToken")))
                                .status())
                .isEqualTo(200);
        assertThat(login(email, "nouveau mot de passe solide").status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ mon compte

    @Test
    void meReturnsTheProfileOfTheTokenOwnerOnly() {
        String email = fx.uniqueEmail("moi");
        register(email);
        String other = fx.uniqueEmail("autre");
        register(other);

        Reply me = api.get("/api/me", login(email, IdentityFixtures.PASSWORD).text("accessToken"));

        assertThat(me.status()).isEqualTo(200);
        assertThat(me.contractViolations("getMe", 200)).isEmpty();
        assertThat(me.json().get("user").get("email").asString()).isEqualTo(email);
        assertThat(me.json().get("customer").get("countryCode").asString()).isEqualTo("ZZ");
        assertThat(me.json().get("customer").get("kycStatus").asString())
                .isEqualTo("NOT_SUBMITTED");
        assertThat(me.body()).doesNotContain(other);
        assertThat(api.get("/api/me", null).status()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ amorçage

    @Autowired com.wtc.identity.application.SuperAdminBootstrap bootstrap;

    @Test
    void theFirstSuperAdminIsCreatedOnlyWhenNoneExistsAndMustEnrollItsSecondFactor()
            throws Exception {
        String email = fx.uniqueEmail("premier");
        assertThat(
                        jdbc.sql("SELECT count(*) FROM user_roles WHERE role_code = 'SUPER_ADMIN'")
                                .query(Long.class)
                                .single())
                .isZero();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> bootstrap.ensureSuperAdmin(email, "motdepasse123"))
                .as("la politique de mot de passe s'applique aussi à l'amorçage")
                .isInstanceOf(com.wtc.shared.error.DomainException.class);
        assertThat(bootstrap.ensureSuperAdmin(email, IdentityFixtures.PASSWORD)).isTrue();
        assertThat(bootstrap.ensureSuperAdmin(fx.uniqueEmail("second"), IdentityFixtures.PASSWORD))
                .as("un seul amorçage : ce n'est pas une porte dérobée")
                .isFalse();

        Reply login = login(email, IdentityFixtures.PASSWORD);
        assertThat(login.status()).isEqualTo(200);
        assertThat(
                        SignedJWT.parse(login.text("accessToken"))
                                .getJWTClaimsSet()
                                .getBooleanClaim("enrollment_only"))
                .isTrue();
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM audit_log WHERE action ="
                                                + " 'SUPER_ADMIN_BOOTSTRAPPED'")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ utilitaires

    private Reply register(String email) {
        return api.post("/api/auth/register", registration(email, IdentityFixtures.PASSWORD, "ZZ"));
    }

    private Api.Reply login(String email, String password) {
        return api.post("/api/auth/login", map("email", email, "password", password));
    }

    private static java.util.Map<String, Object> registration(
            String email, String password, String country) {
        String phone = "+2420" + (1_000_000 + ThreadLocalRandom.current().nextInt(8_999_999));
        return map(
                "email",
                email,
                "password",
                password,
                "firstName",
                "Claire",
                "lastName",
                "Dupont",
                "phone",
                phone,
                "countryCode",
                country);
    }

    private List<String> loginAuditOutcomes(String email) {
        return jdbc.sql(
                        "SELECT outcome FROM login_audit WHERE lower(email_attempted) = lower(:e)"
                                + " ORDER BY id")
                .param("e", email)
                .query(String.class)
                .list();
    }

    /** Déchiffre, comme le ferait le module de notification, le lien déposé dans l'outbox. */
    private String resetTokenFor(String email) throws Exception {
        String payload =
                jdbc.sql(
                                "SELECT payload::text FROM outbox_events WHERE event_type ="
                                    + " 'identity.password-reset-requested' AND payload->>'email' ="
                                    + " :e ORDER BY id DESC LIMIT 1")
                        .param("e", email)
                        .query(String.class)
                        .single();
        String encrypted = json.readTree(payload).get("tokenEncrypted").asString();
        return cipher.decrypt(Base64.getDecoder().decode(encrypted), "outbox:identity-token");
    }
}
