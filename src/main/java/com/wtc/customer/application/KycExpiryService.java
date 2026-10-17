package com.wtc.customer.application;

import com.wtc.customer.application.port.CustomerRepository;
import com.wtc.customer.application.port.KycDocumentRepository;
import com.wtc.customer.application.port.KycDocumentRepository.ExpiryCase;
import com.wtc.customer.domain.KycPolicy;
import com.wtc.shared.outbox.EventPublisher;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tâches planifiées de fin de validité des pièces : les documents échus passent à EXPIRED et
 * l'identité du client est recalculée (un client dont l'unique pièce valable expire ne peut plus
 * envoyer) ; un rappel est émis {@value #REMINDER_DAYS} jours avant l'échéance, une seule fois par
 * document. Idempotent : deux exécutions de suite ne font rien de plus.
 */
@Service
public class KycExpiryService {

    static final int REMINDER_DAYS = 30;

    /**
     * Résultat d'une exécution : documents expirés, clients dont le statut a changé, rappels émis.
     */
    public record Outcome(int expiredDocuments, int customersUpdated, int reminders) {}

    private final KycDocumentRepository documents;
    private final CustomerRepository customers;
    private final EventPublisher events;
    private final Clock clock;

    KycExpiryService(
            KycDocumentRepository documents,
            CustomerRepository customers,
            EventPublisher events,
            Clock clock) {
        this.documents = documents;
        this.customers = customers;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Outcome run() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        List<ExpiryCase> expired = documents.expireApprovedBefore(today);
        Set<Long> touched = new LinkedHashSet<>();
        for (ExpiryCase c : expired) {
            touched.add(c.customerId());
            events.publish(
                    "KYC_DOCUMENT",
                    c.documentId(),
                    "kyc.expired",
                    Map.of(
                            "customerId",
                            c.customerPublicId().toString(),
                            "documentType",
                            c.documentType(),
                            "expiresOn",
                            c.expiresOn().toString()));
        }
        for (long customerId : touched) {
            customers.setKycStatus(
                    customerId,
                    KycPolicy.statusOf(KycService.policyDocs(documents.list(customerId)), today)
                            .name());
        }
        List<ExpiryCase> soon =
                documents.expiringWithoutReminder(today, today.plusDays(REMINDER_DAYS));
        for (ExpiryCase c : soon) {
            events.publish(
                    "KYC_DOCUMENT",
                    c.documentId(),
                    "kyc.expiring",
                    Map.of(
                            "customerId",
                            c.customerPublicId().toString(),
                            "documentType",
                            c.documentType(),
                            "expiresOn",
                            c.expiresOn().toString()));
        }
        return new Outcome(expired.size(), touched.size(), soon.size());
    }
}
