package com.wtc.identity.application;

/** Soit une session ouverte, soit un défi de second facteur à relever ({@code mfaToken}). */
public record LoginResult(Session session, String mfaToken) {

    public static LoginResult authenticated(Session session) {
        return new LoginResult(session, null);
    }

    public static LoginResult mfaRequired(String mfaToken) {
        return new LoginResult(null, mfaToken);
    }

    public boolean isMfaRequired() {
        return mfaToken != null;
    }
}
