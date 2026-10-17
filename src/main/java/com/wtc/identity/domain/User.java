package com.wtc.identity.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Compte utilisateur (instantané immuable lu en base). Le secret TOTP n'en fait pas partie : il se
 * lit à la demande.
 *
 * @param mfaEnabled second facteur actif
 * @param mfaSecretPending un secret TOTP existe (activation commencée ou terminée)
 */
public record User(
        long id,
        UUID publicId,
        String email,
        String passwordHash,
        String displayName,
        String phoneE164,
        UserStatus status,
        Set<Role> roles,
        boolean mfaEnabled,
        boolean mfaSecretPending,
        Long mfaLastUsedStep,
        int failedLoginCount,
        Instant lockedUntil,
        Instant lastLoginAt,
        String photoStorageKey) {

    public User {
        roles = Set.copyOf(roles);
    }

    public boolean isStaff() {
        return roles.stream().anyMatch(Role::isStaff);
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /**
     * Le personnel doit avoir activé le second facteur ; sans cela, sa session ne sert qu'à
     * l'activer.
     */
    public boolean requiresMfaEnrollment() {
        return isStaff() && !mfaEnabled;
    }
}
