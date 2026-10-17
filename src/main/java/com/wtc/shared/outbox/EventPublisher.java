package com.wtc.shared.outbox;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publie un événement métier dans la boîte d'envoi ({@code outbox_events}) <b>dans la transaction
 * de l'appelant</b> : il existe si et seulement si le changement qu'il annonce a été validé. Les
 * consommateurs (notifications, intégrations) le traitent plus tard, hors de toute transaction
 * métier : un échec d'envoi ne peut jamais annuler un transfert.
 */
@Component
public class EventPublisher {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    EventPublisher(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @param aggregateId identifiant interne de l'objet concerné
     * @param payload données utiles au consommateur (jamais de secret en clair : numéros de compte,
     *     mots de passe, jetons)
     */
    public void publish(
            String aggregateType, long aggregateId, String eventType, Map<String, ?> payload) {
        jdbc.sql(
                        """
                        INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
                        VALUES (:type, :id, :event, CAST(:payload AS jsonb))
                        """)
                .param("type", aggregateType)
                .param("id", aggregateId)
                .param("event", eventType)
                .param("payload", json.writeValueAsString(payload))
                .update();
    }
}
