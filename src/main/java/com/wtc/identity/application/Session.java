package com.wtc.identity.application;

/** Jetons remis après une authentification complète. */
public record Session(
        String accessToken, String refreshToken, long expiresInSeconds, UserView user) {}
