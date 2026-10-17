package com.wtc.identity.api;

/** Schéma {@code MfaChallenge} : le défi est à relever sur {@code /auth/mfa/verify}. */
record MfaChallengeResponse(boolean mfaRequired, String mfaToken) {}
