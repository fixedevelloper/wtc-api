package com.wtc.backoffice.application;

import com.wtc.backoffice.application.port.HousekeepingRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nettoyage des données techniques. Les durées de conservation sont des valeurs initiales à valider
 * (hypothèse 21). Les échecs (événements et notifications) ne sont jamais purgés : un humain doit
 * les voir.
 */
@Service
public class Housekeeping {

    /** Nombre de lignes supprimées par catégorie. */
    public record Purged(int refreshTokens, int oneTimeTokens, int events, int notifications) {}

    static final Duration REFRESH_TOKENS = Duration.ofDays(30);
    static final Duration ONE_TIME_TOKENS = Duration.ofDays(7);
    static final Duration EVENTS = Duration.ofDays(60);
    static final Duration NOTIFICATIONS = Duration.ofDays(90);

    private final HousekeepingRepository repository;
    private final Clock clock;

    Housekeeping(HousekeepingRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public Purged run() {
        var now = clock.instant();
        return new Purged(
                repository.purgeRefreshTokens(now.minus(REFRESH_TOKENS)),
                repository.purgeOneTimeTokens(now.minus(ONE_TIME_TOKENS)),
                repository.purgeProcessedEvents(now.minus(EVENTS)),
                repository.purgeDeliveredNotifications(now.minus(NOTIFICATIONS)));
    }
}
