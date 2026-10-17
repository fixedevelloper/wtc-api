package com.wtc.shared.audit;

import com.wtc.shared.web.ClientContext;
import com.wtc.shared.web.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Journal d'audit métier (table {@code audit_log}, immuable en base). Chaque action sensible y
 * laisse une ligne : qui, quoi, sur quelle entité, depuis où. Participe à la transaction de
 * l'appelant : pas d'action sans trace, pas de trace d'une action annulée. Ne jamais y mettre de
 * secret, de jeton ni de mot de passe.
 */
@Component
public class AuditTrail {

    public enum ActorType {
        USER,
        SYSTEM,
        PROVIDER
    }

    private final JdbcClient jdbc;
    private final ClientContext client;

    AuditTrail(JdbcClient jdbc, ClientContext client) {
        this.jdbc = jdbc;
        this.client = client;
    }

    /**
     * Variante pour un auteur connu par son identifiant public (celui du jeton) : son identifiant
     * interne et son libellé (adresse e-mail) sont retrouvés dans la même instruction. Un auteur
     * inconnu est tracé comme absent.
     */
    public void recordAs(
            java.util.UUID actorPublicId,
            String action,
            String entityType,
            String entityId,
            String beforeJson,
            String afterJson) {
        jdbc.sql(
                        """
                        INSERT INTO audit_log (actor_user_id, actor_label, actor_type, action, entity_type, entity_id,
                                               before_state, after_state, ip_address, request_id)
                        SELECT u.id, u.email, 'USER', :action, :entityType, :entityId,
                               CAST(:before AS jsonb), CAST(:after AS jsonb), CAST(:ip AS inet), :requestId
                        FROM (SELECT 1) one LEFT JOIN users u ON u.public_id = :actor
                        """)
                .param("actor", actorPublicId)
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("before", beforeJson)
                .param("after", afterJson)
                .param("ip", client.ip())
                .param("requestId", MDC.get(RequestIdFilter.MDC_KEY))
                .update();
    }

    /**
     * @param actorUserId identifiant interne de l'auteur (nul pour le système)
     * @param actorLabel libellé lisible conservé même si le compte change (adresse e-mail)
     * @param beforeJson état avant (JSON), nul si sans objet
     * @param afterJson état après (JSON), nul si sans objet
     */
    public void record(
            Long actorUserId,
            String actorLabel,
            ActorType actorType,
            String action,
            String entityType,
            String entityId,
            String beforeJson,
            String afterJson) {
        jdbc.sql(
                        """
                        INSERT INTO audit_log (actor_user_id, actor_label, actor_type, action, entity_type, entity_id,
                                               before_state, after_state, ip_address, request_id)
                        VALUES (:actorId, :label, :type, :action, :entityType, :entityId,
                                CAST(:before AS jsonb), CAST(:after AS jsonb), CAST(:ip AS inet), :requestId)
                        """)
                .param("actorId", actorUserId)
                .param("label", actorLabel)
                .param("type", actorType.name())
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("before", beforeJson)
                .param("after", afterJson)
                .param("ip", client.ip())
                .param("requestId", MDC.get(RequestIdFilter.MDC_KEY))
                .update();
    }
}
