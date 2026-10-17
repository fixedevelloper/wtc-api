package com.wtc.transfer.infrastructure;

import com.wtc.transfer.application.PayoutProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Cadence des travailleurs d'envoi et de suivi. Plusieurs instances peuvent tourner en parallèle :
 * la boîte d'envoi se réserve par {@code SKIP LOCKED} et chaque transfert se traite sous verrou de
 * ligne. Désactivable par {@code wtc.payment.worker.enabled=false} (tests, jobs dédiés).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "wtc.payment.worker",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class PayoutScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(PayoutScheduler.class);

    private final PayoutProcessor processor;

    PayoutScheduler(PayoutProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(
            fixedDelayString = "${wtc.payment.worker.outbox-poll-interval:5s}",
            initialDelayString = "10s")
    void sendPendingOrders() {
        try {
            processor.processOutbox();
        } catch (RuntimeException e) {
            LOG.error(
                    "Cycle d'envoi en erreur",
                    e); // le cycle suivant reprend : aucun événement n'est perdu
        }
    }

    @Scheduled(
            fixedDelayString = "${wtc.payment.worker.status-poll-interval:30s}",
            initialDelayString = "15s")
    void followOrders() {
        try {
            processor.pollStatuses();
        } catch (RuntimeException e) {
            LOG.error("Cycle de suivi en erreur", e);
        }
    }
}
