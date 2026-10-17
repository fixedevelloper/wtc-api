package com.wtc.identity.api;

import com.wtc.identity.application.UserView;
import com.wtc.identity.domain.Role;
import java.util.List;
import java.util.UUID;

/** Schéma {@code UserSummary} du contrat. */
record UserSummaryResponse(
        UUID id,
        String email,
        String displayName,
        List<Role> roles,
        boolean mfaEnabled,
        UUID customerId,
        String photoUrl) {

    static UserSummaryResponse from(UserView view) {
        return new UserSummaryResponse(
                view.id(),
                view.email(),
                view.displayName(),
                view.roles().stream().sorted().toList(),
                view.mfaEnabled(),
                view.customerId(),
                view.photoUrl());
    }
}
