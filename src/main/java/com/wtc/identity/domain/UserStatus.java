package com.wtc.identity.domain;

/** État d'un compte. Seul {@link #ACTIVE} peut se connecter. */
public enum UserStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    SUSPENDED
}
