package com.wtc.identity.application;

import com.wtc.identity.domain.Role;
import com.wtc.identity.domain.User;
import java.util.Set;
import java.util.UUID;

/** Vue d'un compte pour le client (schéma {@code UserSummary}). */
public record UserView(
        UUID id,
        String email,
        String displayName,
        Set<Role> roles,
        boolean mfaEnabled,
        UUID customerId,
        String photoUrl) {

    public UserView {
        roles = Set.copyOf(roles);
    }

    public static UserView of(User user, UUID customerId, String photoUrl) {
        return new UserView(
                user.publicId(),
                user.email(),
                user.displayName(),
                user.roles(),
                user.mfaEnabled(),
                customerId,
                photoUrl);
    }
}
