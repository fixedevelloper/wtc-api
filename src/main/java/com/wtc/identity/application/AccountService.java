package com.wtc.identity.application;

import com.wtc.customer.application.CustomerAccounts;
import com.wtc.customer.application.CustomerProfile;
import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.RefreshTokenStore;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.identity.domain.PasswordPolicy;
import com.wtc.identity.domain.Totp;
import com.wtc.identity.domain.User;
import com.wtc.shared.audit.AuditTrail;
import com.wtc.shared.audit.AuditTrail.ActorType;
import com.wtc.shared.config.WtcProperties;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.ratelimit.RateLimiter;
import com.wtc.shared.ratelimit.RateLimiter.Limit;
import com.wtc.shared.security.SecretCipher;
import com.wtc.shared.storage.FileTypes;
import com.wtc.shared.storage.ObjectStorage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mon compte : profil, mot de passe et second facteur de l'utilisateur connecté. */
@Service
public class AccountService {

    private static final String TOTP_ISSUER = "WTC";
    private static final int MAX_PHOTO_BYTES = 2 * 1024 * 1024;

    private final UserRepository users;
    private final RefreshTokenStore refreshTokens;
    private final PasswordHasher hasher;
    private final CustomerAccounts customers;
    private final SecretCipher cipher;
    private final RateLimiter rateLimiter;
    private final AuditTrail audit;
    private final ObjectStorage storage;
    private final PhotoUrls photos;
    private final Clock clock;
    private final int sensitivePerHour;

    AccountService(
            UserRepository users,
            RefreshTokenStore refreshTokens,
            PasswordHasher hasher,
            CustomerAccounts customers,
            SecretCipher cipher,
            RateLimiter rateLimiter,
            AuditTrail audit,
            ObjectStorage storage,
            PhotoUrls photos,
            Clock clock,
            WtcProperties properties) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.hasher = hasher;
        this.customers = customers;
        this.cipher = cipher;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.storage = storage;
        this.photos = photos;
        this.clock = clock;
        this.sensitivePerHour = properties.rateLimits().sensitiveActionsPerUserPerHour();
    }

    public record Me(UserView user, Optional<CustomerProfile> customer) {}

    public record MfaEnrollment(String secret, String otpauthUri) {}

    @Transactional(readOnly = true)
    public Me me(UUID userId) {
        User user = load(userId);
        Optional<CustomerProfile> profile = customers.profileOf(user.id());
        return new Me(
                UserView.of(
                        user, profile.map(CustomerProfile::id).orElse(null), photos.urlOf(user)),
                profile);
    }

    /**
     * Photo de profil : JPEG ou PNG, 2 Mio au plus, reconnue par sa signature. Remplace la
     * précédente.
     */
    @Transactional
    public UserView uploadPhoto(UUID userId, byte[] content) {
        User user = load(userId);
        throttle("photo", user);
        if (content == null || content.length == 0) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Le fichier est vide.");
        }
        if (content.length > MAX_PHOTO_BYTES) {
            throw new DomainException(
                    ErrorCode.PAYLOAD_TOO_LARGE, "Photo trop volumineuse (2 Mio au maximum).");
        }
        FileTypes.Kind kind =
                FileTypes.detect(content)
                        .filter(FileTypes.Kind::isImage)
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.UNSUPPORTED_FILE_TYPE,
                                                "Une image JPEG ou PNG est attendue."));
        String key = "photos/" + user.publicId() + "/" + UUID.randomUUID();
        storage.put(key, content, kind.mediaType());
        users.setPhotoKey(user.id(), key);
        if (user.photoStorageKey() != null) {
            storage.delete(user.photoStorageKey());
        }
        audit.recordAs(userId, "PHOTO_CHANGED", "USER", user.publicId().toString(), null, null);
        User updated = users.findById(user.id()).orElseThrow();
        return UserView.of(
                updated, customers.customerIdOf(user.id()).orElse(null), photos.urlOf(updated));
    }

    @Transactional
    public void changePassword(
            UUID userId, UUID currentSessionId, String currentPassword, String newPassword) {
        User user = load(userId);
        throttle("password", user);
        if (!hasher.matches(currentPassword, user.passwordHash())) {
            throw new DomainException(
                    ErrorCode.INVALID_CURRENT_PASSWORD, "Le mot de passe actuel est incorrect.");
        }
        PasswordPolicy.requireStrong(newPassword, user.email(), "newPassword");
        Instant now = clock.instant();
        users.updatePasswordHash(user.id(), hasher.hash(newPassword), now);
        // Les autres appareils sont déconnectés ; la session courante continue.
        refreshTokens.revokeAllOfUserExcept(user.id(), currentSessionId, now);
        audit.record(
                user.id(),
                user.email(),
                ActorType.USER,
                "PASSWORD_CHANGED",
                "USER",
                user.publicId().toString(),
                null,
                null);
    }

    @Transactional
    public MfaEnrollment startMfaEnrollment(UUID userId) {
        User user = load(userId);
        throttle("mfa-start", user);
        if (user.mfaEnabled()) {
            throw new DomainException(
                    ErrorCode.MFA_ALREADY_ENABLED, "Le second facteur est déjà activé.");
        }
        byte[] secret = Totp.newSecret();
        users.savePendingMfaSecret(
                user.id(),
                cipher.encrypt(Totp.toBase32(secret), AuthService.mfaContext(user.id())));
        return new MfaEnrollment(
                Totp.toBase32(secret), Totp.otpauthUri(TOTP_ISSUER, user.email(), secret));
    }

    @Transactional
    public void confirmMfaEnrollment(UUID userId, String code) {
        User user = load(userId);
        throttle("mfa-confirm", user);
        if (user.mfaEnabled()) {
            throw new DomainException(
                    ErrorCode.MFA_ALREADY_ENABLED, "Le second facteur est déjà activé.");
        }
        byte[] secret =
                secretOf(user)
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.MFA_NOT_ENROLLED,
                                                "Démarrez d'abord l'activation."));
        requireValidCode(user, secret, code);
        users.enableMfa(user.id());
        audit.record(
                user.id(),
                user.email(),
                ActorType.USER,
                "MFA_ENABLED",
                "USER",
                user.publicId().toString(),
                null,
                null);
    }

    @Transactional
    public void disableMfa(UUID userId, String code) {
        User user = load(userId);
        throttle("mfa-disable", user);
        if (user.isStaff()) {
            throw new DomainException(
                    ErrorCode.MFA_REQUIRED_FOR_STAFF,
                    "Le second facteur est obligatoire pour le personnel.");
        }
        if (!user.mfaEnabled()) {
            throw new DomainException(
                    ErrorCode.MFA_NOT_ENROLLED, "Le second facteur n'est pas activé.");
        }
        requireValidCode(user, secretOf(user).orElseThrow(), code);
        users.clearMfa(user.id());
        audit.record(
                user.id(),
                user.email(),
                ActorType.USER,
                "MFA_DISABLED",
                "USER",
                user.publicId().toString(),
                null,
                null);
    }

    private Optional<byte[]> secretOf(User user) {
        return users.findMfaSecret(user.id())
                .map(
                        enc ->
                                Totp.fromBase32(
                                        cipher.decrypt(enc, AuthService.mfaContext(user.id()))));
    }

    private void requireValidCode(User user, byte[] secret, String code) {
        OptionalLong step =
                Totp.verify(secret, code, clock.instant().getEpochSecond(), user.mfaLastUsedStep());
        if (step.isEmpty() || !users.claimMfaStep(user.id(), step.getAsLong())) {
            // 422 et non 401 : l'utilisateur est bien authentifié, un 401 ferait croire au client
            // que son jeton est mauvais.
            throw new DomainException(ErrorCode.MFA_CODE_REJECTED, "Code invalide.");
        }
    }

    private void throttle(String action, User user) {
        rateLimiter.check(
                action + ":user:" + user.id(), new Limit(sensitivePerHour, Duration.ofHours(1)));
    }

    private User load(UUID userId) {
        return users.findByPublicId(userId)
                .orElseThrow(
                        () ->
                                new DomainException(
                                        ErrorCode.UNAUTHENTICATED, "Compte introuvable."));
    }
}
