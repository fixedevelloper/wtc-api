package com.wtc.notification.application.port;

import com.wtc.notification.application.Channel;
import com.wtc.notification.application.NotificationMessage;
import java.time.Instant;
import java.util.List;

public interface NotificationRepository {

    /**
     * @return faux si cette clé d'unicité existait déjà (la notification n'est pas dupliquée)
     */
    boolean enqueue(
            Channel channel,
            String recipient,
            String template,
            String payloadJson,
            String dedupeKey,
            Instant now);

    /**
     * Réserve les notifications dues et repousse leur prochaine échéance de {@code visibility} : si
     * le travailleur meurt, elles redeviennent dues (au moins une fois, jamais perdues).
     */
    List<NotificationMessage> claimDue(int limit, Instant now, java.time.Duration visibility);

    void markSent(long id, Instant now);

    void retryLater(long id, Instant at, String error);

    void markFailed(long id, String error);

    void markSkipped(long id, String reason);
}
