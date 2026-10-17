package com.wtc.notification.application;

import com.wtc.customer.application.CustomerAccounts;
import com.wtc.customer.application.CustomerContact;
import com.wtc.shared.config.WtcProperties;
import com.wtc.shared.outbox.OutboxRepository;
import com.wtc.shared.outbox.OutboxRepository.Event;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transforme les événements métier de la boîte d'envoi en notifications : qui prévenir, par quel
 * canal, avec quel modèle. Une clé d'unicité par événement et par canal empêche tout doublon, même
 * si l'événement est traité deux fois.
 */
@Component
public class EventBridge {

    private static final Logger LOG = LoggerFactory.getLogger(EventBridge.class);
    static final List<String> EVENT_TYPES =
            List.of(
                    "identity.password-reset-requested",
                    "identity.staff-invited",
                    "transfer.created",
                    "transfer.completed",
                    "transfer.refunded",
                    "kyc.reviewed",
                    "kyc.expired",
                    "kyc.expiring");

    private final OutboxRepository outbox;
    private final NotificationService notifications;
    private final CustomerAccounts customers;
    private final JsonMapper json;
    private final Clock clock;
    private final WtcProperties.NotificationWorker config;
    private final TransactionTemplate tx;

    EventBridge(
            OutboxRepository outbox,
            NotificationService notifications,
            CustomerAccounts customers,
            JsonMapper json,
            Clock clock,
            WtcProperties properties,
            PlatformTransactionManager txManager) {
        this.outbox = outbox;
        this.notifications = notifications;
        this.customers = customers;
        this.json = json;
        this.clock = clock;
        this.config = properties.notification().worker();
        this.tx = new TransactionTemplate(txManager);
    }

    /** Traite les événements dus ; retourne leur nombre. */
    public int bridgeDue() {
        outbox.releaseStale(clock.instant().minus(Duration.ofMinutes(10)));
        List<Event> events =
                tx.execute(s -> outbox.claimDue(EVENT_TYPES, config.batchSize(), clock.instant()));
        if (events == null) {
            return 0;
        }
        for (Event event : events) {
            try {
                tx.executeWithoutResult(s -> handle(event));
                outbox.done(event.id());
            } catch (RuntimeException e) {
                LOG.warn(
                        "Événement {} ({}) non traduit en notification : {}",
                        event.id(),
                        event.type(),
                        e.getClass().getSimpleName());
                if (event.attempts() >= config.maxAttempts()) {
                    outbox.fail(event.id(), e.getClass().getSimpleName());
                } else {
                    outbox.retryLater(
                            event.id(),
                            clock.instant().plusSeconds(60L * event.attempts()),
                            e.getClass().getSimpleName());
                }
            }
        }
        return events.size();
    }

    private void handle(Event event) {
        JsonNode payload = payloadOf(event);
        switch (event.type()) {
            case "identity.password-reset-requested" ->
                    email(
                            payload.path("email").asString(),
                            "password-reset",
                            copy(payload),
                            "reset:" + event.id());
            case "identity.staff-invited" ->
                    email(
                            payload.path("email").asString(),
                            "staff-invitation",
                            copy(payload),
                            "invite:" + event.id());
            case "transfer.created", "transfer.completed", "transfer.refunded" ->
                    transfer(event, payload);
            case "kyc.reviewed" -> kyc(event, payload);
            case "kyc.expired", "kyc.expiring" -> kycExpiry(event, payload);
            default -> LOG.debug("Événement {} ignoré", event.type());
        }
    }

    private void transfer(Event event, JsonNode payload) {
        Optional<CustomerContact> contact =
                customers.contactOf(UUID.fromString(payload.path("customerId").asString()));
        if (contact.isEmpty()) {
            return;
        }
        String template = event.type().replace("transfer.", "transfer-");
        String transferId = payload.path("transferId").asString();
        Map<String, Object> content = copy(payload);
        content.put("displayName", contact.get().displayName());
        notifyCustomer(
                contact.get(), template, content, "transfer:" + transferId + ":" + event.type());
    }

    private void kyc(Event event, JsonNode payload) {
        Optional<CustomerContact> contact =
                customers.contactOf(UUID.fromString(payload.path("customerId").asString()));
        if (contact.isEmpty()) {
            return;
        }
        boolean approved = "APPROVED".equals(payload.path("decision").asString());
        Map<String, Object> content = copy(payload);
        content.put("displayName", contact.get().displayName());
        notifyCustomer(
                contact.get(),
                approved ? "kyc-approved" : "kyc-rejected",
                content,
                "kyc:" + event.id());
    }

    private void kycExpiry(Event event, JsonNode payload) {
        Optional<CustomerContact> contact =
                customers.contactOf(UUID.fromString(payload.path("customerId").asString()));
        if (contact.isEmpty()) {
            return;
        }
        Map<String, Object> content = copy(payload);
        content.put("displayName", contact.get().displayName());
        String template = "kyc.expired".equals(event.type()) ? "kyc-expired" : "kyc-expiring";
        notifyCustomer(contact.get(), template, content, "kyc:" + event.id());
    }

    /**
     * Courriel si le client en a un, WhatsApp s'il a un téléphone (le modèle décide si le canal est
     * concerné).
     */
    private void notifyCustomer(
            CustomerContact contact, String template, Map<String, Object> content, String dedupe) {
        if (contact.email() != null && !contact.email().isBlank()) {
            notifications.enqueue(
                    Channel.EMAIL, contact.email(), template, content, dedupe + ":email");
        }
        if (contact.phone() != null && !contact.phone().isBlank()) {
            notifications.enqueue(
                    Channel.WHATSAPP, contact.phone(), template, content, dedupe + ":whatsapp");
        }
    }

    private void email(
            String address, String template, Map<String, Object> content, String dedupe) {
        if (address != null && !address.isBlank()) {
            notifications.enqueue(Channel.EMAIL, address, template, content, dedupe + ":email");
        }
    }

    private JsonNode payloadOf(Event event) {
        return json.readTree(outbox.payloadOf(event.id()));
    }

    private Map<String, Object> copy(JsonNode payload) {
        Map<String, Object> map = new LinkedHashMap<>();
        payload.properties()
                .forEach(
                        e ->
                                map.put(
                                        e.getKey(),
                                        e.getValue().isString()
                                                ? e.getValue().asString()
                                                : json.convertValue(e.getValue(), Object.class)));
        return map;
    }
}
