package com.wtc.backoffice.infrastructure;

import com.wtc.backoffice.application.Housekeeping;
import com.wtc.backoffice.application.WebTokenService;
import com.wtc.customer.application.KycExpiryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Tâches périodiques d'entretien. Chacune est idempotente (une instruction qui ne retrouve rien à
 * faire ne fait rien) : plusieurs instances, ou une exécution manquée, sont sans conséquence. Une
 * erreur est journalisée et la tâche repart au cycle suivant.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "wtc.tasks",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
class ScheduledTasks {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledTasks.class);

    private final KycExpiryService kyc;
    private final WebTokenService webTokens;
    private final Housekeeping housekeeping;

    ScheduledTasks(KycExpiryService kyc, WebTokenService webTokens, Housekeeping housekeeping) {
        this.kyc = kyc;
        this.webTokens = webTokens;
        this.housekeeping = housekeeping;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    void expireKyc() {
        try {
            var outcome = kyc.run();
            if (outcome.expiredDocuments() + outcome.reminders() > 0) {
                LOG.info(
                        "KYC : {} documents expirés, {} clients mis à jour, {} rappels",
                        outcome.expiredDocuments(),
                        outcome.customersUpdated(),
                        outcome.reminders());
            }
        } catch (RuntimeException e) {
            LOG.error("Expiration KYC en erreur", e);
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    void expireWebTokens() {
        try {
            webTokens.expireDue();
        } catch (RuntimeException e) {
            LOG.error("Expiration des jetons web en erreur", e);
        }
    }

    @Scheduled(fixedDelay = 21_600_000, initialDelay = 300_000)
    void purge() {
        try {
            var purged = housekeeping.run();
            LOG.info("Nettoyage : {}", purged);
        } catch (RuntimeException e) {
            LOG.error("Nettoyage en erreur", e);
        }
    }
}
