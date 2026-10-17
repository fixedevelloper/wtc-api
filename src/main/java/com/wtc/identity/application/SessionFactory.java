package com.wtc.identity.application;

import com.wtc.customer.application.CustomerAccounts;
import com.wtc.identity.application.port.AccessTokenIssuer;
import com.wtc.identity.application.port.RefreshTokenStore;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.identity.domain.Role;
import com.wtc.identity.domain.User;
import com.wtc.shared.config.WtcProperties;
import com.wtc.shared.security.Tokens;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Fabrique les jetons d'une session : jeton d'accès signé et refresh token opaque (empreinte seule
 * en base).
 */
@Component
class SessionFactory {

    private final UserRepository users;
    private final RefreshTokenStore refreshTokens;
    private final AccessTokenIssuer accessTokens;
    private final CustomerAccounts customers;
    private final PhotoUrls photos;
    private final WtcProperties.Jwt jwt;

    SessionFactory(
            UserRepository users,
            RefreshTokenStore refreshTokens,
            AccessTokenIssuer accessTokens,
            CustomerAccounts customers,
            PhotoUrls photos,
            WtcProperties properties) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.accessTokens = accessTokens;
        this.customers = customers;
        this.photos = photos;
        this.jwt = properties.security().jwt();
    }

    /** Nouvelle session (nouvelle famille de refresh tokens). */
    Session open(User user, Instant now, RequestMeta meta) {
        UUID family = UUID.randomUUID();
        String refresh = Tokens.random();
        refreshTokens.insert(
                user.id(),
                family,
                Tokens.sha256(refresh),
                now,
                now.plus(jwt.refreshTtl()),
                meta.userAgent(),
                meta.ip());
        return build(user, family, refresh, now);
    }

    /** Session déjà créée (rotation) : on ne fait que signer le jeton d'accès. */
    Session build(User user, UUID family, String refreshToken, Instant now) {
        // Le personnel sans second facteur n'obtient aucun droit : sa session ne sert qu'à
        // l'activer.
        boolean enrollmentOnly = user.requiresMfaEnrollment();
        Set<String> roles =
                enrollmentOnly
                        ? Set.of()
                        : user.roles().stream().map(Role::name).collect(Collectors.toSet());
        Set<String> permissions = enrollmentOnly ? Set.of() : users.permissionsOf(user.roles());
        UUID customerId = customers.customerIdOf(user.id()).orElse(null);
        AccessTokenIssuer.Issued access =
                accessTokens.issue(
                        new AccessTokenIssuer.Claims(
                                user.publicId(),
                                roles,
                                permissions,
                                family,
                                enrollmentOnly,
                                enrollmentOnly ? null : customerId),
                        now);
        return new Session(
                access.token(),
                refreshToken,
                access.expiresInSeconds(),
                UserView.of(user, customerId, photos.urlOf(user)));
    }
}
