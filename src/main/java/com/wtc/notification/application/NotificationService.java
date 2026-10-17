package com.wtc.notification.application;

import com.wtc.notification.application.port.NotificationRepository;
import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mise en file d'une notification. Appelable depuis n'importe quel module : la file ne fait que
 * mémoriser, l'envoi se fait plus tard par le répartiteur, hors de toute transaction métier.
 */
@Service
public class NotificationService {

    private final NotificationRepository queue;
    private final JsonMapper json;
    private final Clock clock;

    NotificationService(NotificationRepository queue, JsonMapper json, Clock clock) {
        this.queue = queue;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @return faux si cette notification (même {@code dedupeKey}) était déjà en file ou envoyée
     */
    @Transactional
    public boolean enqueue(
            Channel channel,
            String recipient,
            String template,
            Map<String, ?> payload,
            String dedupeKey) {
        return queue.enqueue(
                channel,
                recipient,
                template,
                json.writeValueAsString(payload),
                dedupeKey,
                clock.instant());
    }
}
