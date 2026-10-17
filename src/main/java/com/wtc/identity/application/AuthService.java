package com.wtc.identity.application;

import com.wtc.customer.application.CustomerAccounts;
import com.wtc.identity.application.port.AuthNotifier;
import com.wtc.identity.application.port.LoginAuditRepository;
import com.wtc.identity.application.port.LoginAuditRepository.Outcome;
import com.wtc.identity.application.port.OneTimeTokenStore;
import com.wtc.identity.application.port.OneTimeTokenStore.Purpose;
import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.RefreshTokenStore;
import com.wtc.identity.application.port.RefreshTokenStore.RotationOutcome;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.identity.domain.PasswordPolicy;
import com.wtc.identity.domain.Role;
import com.wtc.identity.domain.Totp;
import com.wtc.identity.domain.User;
import com.wtc.identity.domain.UserStatus;
import com.wtc.shared.audit.AuditTrail;
import com.wtc.shared.audit.AuditTrail.ActorType;
import com.wtc.shared.config.WtcProperties;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.RateLimitedException;
import com.wtc.shared.ratelimit.RateLimiter;
import com.wtc.shared.ratelimit.RateLimiter.Limit;
import com.wtc.shared.security.SecretCipher;
import com.wtc.shared.security.Tokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentification : inscription, connexion (avec second facteur), renouvellement par rotation,
 * déconnexion et réinitialisation du mot de passe.
 *
 * <p>Règles de sécurité appliquées ici :
 *
 * <ul>
 *   <li>un compte inconnu et un mauvais mot de passe renvoient la même erreur, au même coût de
 *       calcul ;
 *   <li>les échecs sont comptés par compte (verrouillage temporaire) <em>et</em> par adresse e-mail
 *       / IP (débit) ;
 *   <li>un refresh token rejoué révoque toute sa famille ;
 *   <li>les chemins d'échec ne sont pas transactionnels de bout en bout : compteurs, verrous et
 *       audit sont validés avant que l'erreur ne soit levée.
 * </ul>
 */
@Service
public class AuthService {

    private static final Duration MFA_CHALLENGE_TTL = Duration.ofMinutes(5);
    private static final Duration RESET_TTL = Duration.ofHours(1);
    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final UserRepository users;
    private final RefreshTokenStore refreshTokens;
    private final OneTimeTokenStore oneTimeTokens;
    private final LoginAuditRepository loginAudit;
    private final PasswordHasher hasher;
    private final SessionFactory sessions;
    private final AuthNotifier notifier;
    private final CustomerAccounts customers;
    private final SecretCipher cipher;
    private final RateLimiter rateLimiter;
    private final AuditTrail audit;
    private final Clock clock;
    private final WtcProperties.Login loginPolicy;
    private final WtcProperties.RateLimits limits;
    private final WtcProperties.Jwt jwt;

    AuthService(
            UserRepository users,
            RefreshTokenStore refreshTokens,
            OneTimeTokenStore oneTimeTokens,
            LoginAuditRepository loginAudit,
            PasswordHasher hasher,
            SessionFactory sessions,
            AuthNotifier notifier,
            CustomerAccounts customers,
            SecretCipher cipher,
            RateLimiter rateLimiter,
            AuditTrail audit,
            Clock clock,
            WtcProperties properties) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.oneTimeTokens = oneTimeTokens;
        this.loginAudit = loginAudit;
        this.hasher = hasher;
        this.sessions = sessions;
        this.notifier = notifier;
        this.customers = customers;
        this.cipher = cipher;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.clock = clock;
        this.loginPolicy = properties.security().login();
        this.limits = properties.rateLimits();
        this.jwt = properties.security().jwt();
    }

    public record RegisterCommand(
            String email,
            String password,
            String firstName,
            String lastName,
            String phone,
            String countryCode) {}

    // ------------------------------------------------------------------ inscription

    @Transactional
    public UserView register(RegisterCommand cmd, RequestMeta meta) {
        rateLimiter.check(
                "register:ip:" + meta.ip(), new Limit(limits.registerPerIpPerHour(), HOUR));
        String email = normalizeEmail(cmd.email());
        PasswordPolicy.requireStrong(cmd.password(), email, "password");
        if (users.emailExists(email)) {
            throw new DomainException(
                    ErrorCode.EMAIL_ALREADY_REGISTERED, "Cette adresse e-mail est déjà utilisée.");
        }
        if (users.phoneExists(cmd.phone())) {
            throw new DomainException(
                    ErrorCode.PHONE_ALREADY_REGISTERED, "Ce numéro de téléphone est déjà utilisé.");
        }
        String displayName = (cmd.firstName().trim() + " " + cmd.lastName().trim()).trim();
        long userId;
        try {
            userId =
                    users.insert(
                            new UserRepository.NewUser(
                                    email,
                                    hasher.hash(cmd.password()),
                                    displayName,
                                    cmd.phone(),
                                    Set.of(Role.CUSTOMER)));
        } catch (DuplicateKeyException race) {
            // Deux inscriptions simultanées avec la même adresse : l'index unique tranche.
            throw new DomainException(
                    ErrorCode.EMAIL_ALREADY_REGISTERED, "Cette adresse e-mail est déjà utilisée.");
        }
        UUID customerId =
                customers.registerIndividual(
                        userId,
                        cmd.firstName().trim(),
                        cmd.lastName().trim(),
                        cmd.phone(),
                        email,
                        cmd.countryCode());
        User user = users.findById(userId).orElseThrow();
        audit.record(
                userId,
                email,
                ActorType.USER,
                "USER_REGISTERED",
                "USER",
                user.publicId().toString(),
                null,
                null);
        return UserView.of(user, customerId, null);
    }

    // ------------------------------------------------------------------ connexion

    public LoginResult login(String rawEmail, String password, RequestMeta meta) {
        String email = normalizeEmail(rawEmail);
        rateLimiter.check(
                "login:email:" + email, new Limit(limits.loginPerEmailPerMinute(), MINUTE));
        rateLimiter.check("login:ip:" + meta.ip(), new Limit(limits.loginPerIpPerMinute(), MINUTE));
        Instant now = clock.instant();

        Optional<User> found = users.findByEmail(email);
        if (found.isEmpty()) {
            hasher.burnTime(password);
            loginAudit.record(
                    null, email, Outcome.BAD_CREDENTIALS, meta.ip(), meta.userAgent(), now);
            throw invalidCredentials();
        }
        User user = found.get();
        if (user.isLocked(now)) {
            // Pas de vérification du mot de passe pendant le verrou : il ne sert pas d'oracle.
            loginAudit.record(user.id(), email, Outcome.LOCKED, meta.ip(), meta.userAgent(), now);
            throw new RateLimitedException(Duration.between(now, user.lockedUntil()));
        }
        if (!hasher.matches(password, user.passwordHash())) {
            User after =
                    users.recordFailedLogin(
                            user.id(),
                            loginPolicy.maxFailedAttempts(),
                            loginPolicy.lockDuration(),
                            now);
            loginAudit.record(
                    user.id(), email, Outcome.BAD_CREDENTIALS, meta.ip(), meta.userAgent(), now);
            if (after.isLocked(now)) {
                audit.record(
                        user.id(),
                        email,
                        ActorType.SYSTEM,
                        "ACCOUNT_LOCKED",
                        "USER",
                        user.publicId().toString(),
                        null,
                        null);
            }
            throw invalidCredentials();
        }
        if (user.status() != UserStatus.ACTIVE) {
            loginAudit.record(
                    user.id(), email, Outcome.SUSPENDED, meta.ip(), meta.userAgent(), now);
            throw new DomainException(ErrorCode.ACCESS_DENIED, "Ce compte n'est pas actif.");
        }
        if (hasher.needsRehash(user.passwordHash())) {
            users.updatePasswordHash(user.id(), hasher.hash(password), now);
        }
        if (user.mfaEnabled()) {
            String challenge = Tokens.random();
            oneTimeTokens.invalidateAll(user.id(), Purpose.MFA_CHALLENGE, now);
            oneTimeTokens.create(
                    user.id(),
                    Purpose.MFA_CHALLENGE,
                    Tokens.sha256(challenge),
                    now.plus(MFA_CHALLENGE_TTL));
            loginAudit.record(
                    user.id(), email, Outcome.MFA_REQUIRED, meta.ip(), meta.userAgent(), now);
            return LoginResult.mfaRequired(challenge);
        }
        return LoginResult.authenticated(completeLogin(user, email, now, meta));
    }

    public Session verifyMfa(String mfaToken, String code, RequestMeta meta) {
        Instant now = clock.instant();
        String hash = Tokens.sha256(mfaToken);
        rateLimiter.check("mfa:ip:" + meta.ip(), new Limit(limits.loginPerIpPerMinute(), MINUTE));
        OneTimeTokenStore.Token challenge =
                oneTimeTokens
                        .findUsable(Purpose.MFA_CHALLENGE, hash, now)
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.INVALID_MFA_CODE,
                                                "Défi expiré ou inconnu : reconnectez-vous."));
        User user = users.findById(challenge.userId()).orElseThrow();
        if (!oneTimeTokens.registerAttempt(challenge.id(), limits.mfaAttemptsPerChallenge(), now)) {
            throw new DomainException(
                    ErrorCode.INVALID_MFA_CODE, "Trop d'essais : reconnectez-vous.");
        }
        if (user.isLocked(now)) {
            throw new RateLimitedException(Duration.between(now, user.lockedUntil()));
        }
        byte[] secret =
                users.findMfaSecret(user.id())
                        .map(enc -> Totp.fromBase32(cipher.decrypt(enc, mfaContext(user.id()))))
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.INVALID_MFA_CODE,
                                                "Second facteur indisponible."));
        OptionalLong step = Totp.verify(secret, code, now.getEpochSecond(), user.mfaLastUsedStep());
        if (step.isEmpty() || !users.claimMfaStep(user.id(), step.getAsLong())) {
            users.recordFailedLogin(
                    user.id(), loginPolicy.maxFailedAttempts(), loginPolicy.lockDuration(), now);
            loginAudit.record(
                    user.id(), user.email(), Outcome.MFA_FAILED, meta.ip(), meta.userAgent(), now);
            throw new DomainException(ErrorCode.INVALID_MFA_CODE, "Code invalide.");
        }
        if (!oneTimeTokens.consume(
                challenge.id(), now)) { // un défi ne sert qu'une fois, même en concurrence
            throw new DomainException(
                    ErrorCode.INVALID_MFA_CODE, "Défi déjà utilisé : reconnectez-vous.");
        }
        return completeLogin(users.findById(user.id()).orElseThrow(), user.email(), now, meta);
    }

    private Session completeLogin(User user, String email, Instant now, RequestMeta meta) {
        users.recordSuccessfulLogin(user.id(), now);
        loginAudit.record(user.id(), email, Outcome.SUCCESS, meta.ip(), meta.userAgent(), now);
        return sessions.open(user, now, meta);
    }

    // ------------------------------------------------------------------ renouvellement et
    // déconnexion

    public Session refresh(String presentedToken, RequestMeta meta) {
        rateLimiter.check(
                "refresh:ip:" + meta.ip(), new Limit(limits.refreshPerIpPerMinute(), MINUTE));
        Instant now = clock.instant();
        String newToken = Tokens.random();
        RefreshTokenStore.Rotation rotation =
                refreshTokens.rotate(
                        Tokens.sha256(presentedToken),
                        Tokens.sha256(newToken),
                        now,
                        stored -> {
                            Instant ceiling = stored.familyStartedAt().plus(jwt.refreshMaxAge());
                            Instant wanted = now.plus(jwt.refreshTtl());
                            return wanted.isBefore(ceiling) ? wanted : ceiling;
                        },
                        meta.userAgent(),
                        meta.ip());
        if (rotation.outcome() == RotationOutcome.REUSED) {
            User owner = users.findById(rotation.previous().userId()).orElseThrow();
            audit.record(
                    owner.id(),
                    owner.email(),
                    ActorType.SYSTEM,
                    "REFRESH_TOKEN_REUSE_DETECTED",
                    "USER",
                    owner.publicId().toString(),
                    null,
                    null);
            throw new DomainException(
                    ErrorCode.REFRESH_TOKEN_REUSED, "Session révoquée : reconnectez-vous.");
        }
        if (rotation.outcome() == RotationOutcome.INVALID) {
            throw new DomainException(
                    ErrorCode.INVALID_REFRESH_TOKEN, "Session expirée : reconnectez-vous.");
        }
        User user = users.findById(rotation.previous().userId()).orElseThrow();
        if (user.status() != UserStatus.ACTIVE || user.isLocked(now)) {
            refreshTokens.revokeFamily(rotation.previous().familyId(), now);
            throw new DomainException(
                    ErrorCode.INVALID_REFRESH_TOKEN, "Session expirée : reconnectez-vous.");
        }
        return sessions.build(user, rotation.previous().familyId(), newToken, now);
    }

    public void logout(String presentedToken) {
        refreshTokens
                .find(Tokens.sha256(presentedToken))
                .ifPresent(
                        stored -> refreshTokens.revokeFamily(stored.familyId(), clock.instant()));
    }

    // ------------------------------------------------------------------ réinitialisation du mot de
    // passe

    @Transactional
    public void requestPasswordReset(String rawEmail, RequestMeta meta) {
        String email = normalizeEmail(rawEmail);
        rateLimiter.check(
                "reset:ip:" + meta.ip(), new Limit(limits.passwordResetPerIpPerHour(), HOUR));
        rateLimiter.check(
                "reset:email:" + email, new Limit(limits.passwordResetPerEmailPerHour(), HOUR));
        Instant now = clock.instant();
        // Réponse identique que le compte existe ou non (pas d'énumération de comptes).
        users.findByEmail(email)
                .filter(u -> u.status() == UserStatus.ACTIVE)
                .ifPresent(
                        user -> {
                            oneTimeTokens.invalidateAll(user.id(), Purpose.PASSWORD_RESET, now);
                            String raw = Tokens.random();
                            Instant expiresAt = now.plus(RESET_TTL);
                            oneTimeTokens.create(
                                    user.id(),
                                    Purpose.PASSWORD_RESET,
                                    Tokens.sha256(raw),
                                    expiresAt);
                            notifier.passwordResetRequested(user, raw, expiresAt);
                            audit.record(
                                    user.id(),
                                    email,
                                    ActorType.USER,
                                    "PASSWORD_RESET_REQUESTED",
                                    "USER",
                                    user.publicId().toString(),
                                    null,
                                    null);
                        });
    }

    @Transactional
    public void confirmPasswordReset(String rawToken, String newPassword, RequestMeta meta) {
        rateLimiter.check(
                "reset-confirm:ip:" + meta.ip(),
                new Limit(limits.passwordResetPerIpPerHour(), HOUR));
        Instant now = clock.instant();
        OneTimeTokenStore.Token token =
                oneTimeTokens
                        .findUsable(Purpose.PASSWORD_RESET, Tokens.sha256(rawToken), now)
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.INVALID_RESET_TOKEN,
                                                "Lien invalide ou expiré."));
        User user = users.findById(token.userId()).orElseThrow();
        // La politique est vérifiée avant de consommer le lien : un mot de passe refusé ne le brûle
        // pas.
        PasswordPolicy.requireStrong(newPassword, user.email(), "newPassword");
        if (!oneTimeTokens.consume(token.id(), now)) {
            throw new DomainException(ErrorCode.INVALID_RESET_TOKEN, "Lien invalide ou expiré.");
        }
        users.updatePasswordHash(user.id(), hasher.hash(newPassword), now);
        users.clearLock(user.id());
        refreshTokens.revokeAllOfUser(user.id(), now); // toutes les sessions ouvertes sont fermées
        audit.record(
                user.id(),
                user.email(),
                ActorType.USER,
                "PASSWORD_RESET_COMPLETED",
                "USER",
                user.publicId().toString(),
                null,
                null);
    }

    // ------------------------------------------------------------------ utilitaires

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    static String mfaContext(long userId) {
        return "user-mfa:" + userId;
    }

    private static DomainException invalidCredentials() {
        return new DomainException(
                ErrorCode.INVALID_CREDENTIALS, "Adresse e-mail ou mot de passe incorrect.");
    }
}
