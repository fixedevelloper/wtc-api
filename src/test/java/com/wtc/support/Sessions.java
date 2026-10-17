package com.wtc.support;

import static com.wtc.support.Api.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.identity.IdentityFixtures;
import com.wtc.identity.domain.Role;
import com.wtc.identity.domain.Totp;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ouvre des sessions complètes pour les tests : client inscrit ou membre du personnel avec 2FA
 * active.
 */
public final class Sessions {

    /** Jetons d'un compte connecté. */
    public record Session(
            String email,
            String accessToken,
            String refreshToken,
            String userId,
            String customerId) {}

    private Sessions() {}

    public static Session customer(Api api, IdentityFixtures fx, String prefix) {
        String email = fx.uniqueEmail(prefix);
        String phone = "+2420" + (1_000_000 + ThreadLocalRandom.current().nextInt(8_999_999));
        Api.Reply registered =
                api.post(
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
        assertThat(registered.status()).isEqualTo(201);
        Api.Reply login =
                api.post(
                        "/api/auth/login",
                        map("email", email, "password", IdentityFixtures.PASSWORD));
        assertThat(login.status()).isEqualTo(200);
        return new Session(
                email,
                login.text("accessToken"),
                login.text("refreshToken"),
                registered.text("id"),
                registered.text("customerId"));
    }

    public static Session staff(Api api, IdentityFixtures fx, TestClock clock, Role role) {
        String email = fx.uniqueEmail(role.name().toLowerCase().replace('_', '-'));
        fx.createUser(email, Set.of(role));
        String enroll =
                api.post(
                                "/api/auth/login",
                                map("email", email, "password", IdentityFixtures.PASSWORD))
                        .text("accessToken");
        String secret = api.post("/api/me/mfa/enrollment", null, enroll).text("secret");
        assertThat(
                        api.post(
                                        "/api/me/mfa/confirmation",
                                        map("code", code(secret, clock)),
                                        enroll)
                                .status())
                .isEqualTo(204);
        String challenge =
                api.post(
                                "/api/auth/login",
                                map("email", email, "password", IdentityFixtures.PASSWORD))
                        .text("mfaToken");
        clock.advance(Duration.ofSeconds(30));
        Api.Reply session =
                api.post(
                        "/api/auth/mfa/verify",
                        map("mfaToken", challenge, "code", code(secret, clock)));
        assertThat(session.status()).isEqualTo(200);
        return new Session(
                email,
                session.text("accessToken"),
                session.text("refreshToken"),
                session.json().get("user").get("id").asString(),
                null);
    }

    private static String code(String secret, TestClock clock) {
        return Totp.codeAt(Totp.fromBase32(secret), Totp.stepAt(clock.instant().getEpochSecond()));
    }
}
