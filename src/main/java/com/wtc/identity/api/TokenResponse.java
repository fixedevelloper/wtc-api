package com.wtc.identity.api;

import com.wtc.identity.application.Session;

/** Schéma {@code TokenResponse} du contrat. */
record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserSummaryResponse user) {

    static TokenResponse from(Session session) {
        return new TokenResponse(
                session.accessToken(),
                session.refreshToken(),
                "Bearer",
                session.expiresInSeconds(),
                UserSummaryResponse.from(session.user()));
    }
}
