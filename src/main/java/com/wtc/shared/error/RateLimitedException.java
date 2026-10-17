package com.wtc.shared.error;

import java.time.Duration;

/** Plafond de requêtes dépassé : la réponse porte {@code Retry-After}. */
public class RateLimitedException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    public RateLimitedException(Duration retryAfter) {
        super(ErrorCode.RATE_LIMITED, "Trop de requêtes. Réessayez dans quelques instants.");
        this.retryAfterSeconds = Math.max(1, retryAfter.toSeconds());
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
