package com.wtc.shared.ratelimit;

import java.time.Duration;

/**
 * Limiteur de débit par clé. Lève {@link com.wtc.shared.error.RateLimitedException} quand le
 * plafond est atteint. L'implémentation fournie est locale à l'instance ; avec plusieurs instances,
 * la limite effective est multipliée par leur nombre : prévoir un magasin partagé (Redis) avant de
 * passer à l'échelle.
 */
public interface RateLimiter {

    /** Plafond : {@code capacity} requêtes par fenêtre {@code window}. */
    record Limit(int capacity, Duration window) {}

    void check(String key, Limit limit);
}
