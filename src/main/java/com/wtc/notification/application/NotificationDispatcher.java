package com.wtc.notification.application;

import com.wtc.notification.application.port.NotificationRepository;
import com.wtc.shared.config.WtcProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Envoie les notifications dues. Livraison <b>au moins une fois</b> : une notification est réservée
 * (échéance repoussée de 5 minutes) avant l'envoi, puis marquée envoyée ; si le processus meurt
 * entre les deux, elle repartira (un doublon est préférable à un message perdu). L'envoi n'a lieu
 * dans aucune transaction de base, et rien de ce qui se passe ici ne peut affecter un transfert.
 */
@Component
public class NotificationDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final Duration VISIBILITY = Duration.ofMinutes(5);
    private static final Duration MAX_BACKOFF = Duration.ofHours(3);

    private final NotificationRepository queue;
    private final NotificationTemplates templates;
    private final Map<Channel, NotificationSender> senders = new EnumMap<>(Channel.class);
    private final JsonMapper json;
    private final Clock clock;
    private final WtcProperties.NotificationWorker config;
    private final TransactionTemplate tx;

    NotificationDispatcher(
            NotificationRepository queue,
            NotificationTemplates templates,
            List<NotificationSender> allSenders,
            JsonMapper json,
            Clock clock,
            WtcProperties properties,
            PlatformTransactionManager txManager) {
        this.queue = queue;
        this.templates = templates;
        allSenders.forEach(s -> senders.put(s.channel(), s));
        this.json = json;
        this.clock = clock;
        this.config = properties.notification().worker();
        this.tx = new TransactionTemplate(txManager);
    }

    /** Traite les notifications dues ; retourne leur nombre. */
    public int dispatchDue() {
        List<NotificationMessage> due =
                tx.execute(s -> queue.claimDue(config.batchSize(), clock.instant(), VISIBILITY));
        if (due == null) {
            return 0;
        }
        due.forEach(this::deliver);
        return due.size();
    }

    private void deliver(NotificationMessage message) {
        NotificationSender sender = senders.get(message.channel());
        if (sender == null) {
            queue.markSkipped(message.id(), "Canal " + message.channel() + " désactivé");
            return;
        }
        SendResult result;
        try {
            JsonNode payload = json.readTree(message.payloadJson());
            Optional<Rendered> rendered =
                    templates.render(message.template(), message.channel(), payload);
            if (rendered.isEmpty()) {
                queue.markSkipped(
                        message.id(),
                        "Aucun modèle « " + message.template() + " » pour " + message.channel());
                return;
            }
            result = sender.send(message, rendered.get());
        } catch (RuntimeException e) {
            // Un modèle ou un jeton illisible ne se répare pas en réessayant.
            LOG.warn(
                    "Notification {} impossible à préparer : {}",
                    message.id(),
                    e.getClass().getSimpleName());
            queue.markFailed(
                    message.id(), "Préparation impossible : " + e.getClass().getSimpleName());
            return;
        }
        switch (result.kind()) {
            case SENT -> queue.markSent(message.id(), clock.instant());
            case SKIPPED -> queue.markSkipped(message.id(), result.detail());
            case PERMANENT -> queue.markFailed(message.id(), result.detail());
            case RETRY -> {
                if (message.attempts() >= config.maxAttempts()) {
                    queue.markFailed(
                            message.id(),
                            "Abandon après " + message.attempts() + " essais : " + result.detail());
                } else {
                    queue.retryLater(
                            message.id(),
                            clock.instant().plus(backoff(message.attempts())),
                            result.detail());
                }
            }
        }
    }

    private static Duration backoff(int attempt) {
        Duration delay = Duration.ofSeconds(30L * (1L << Math.min(Math.max(attempt - 1, 0), 12)));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }
}
