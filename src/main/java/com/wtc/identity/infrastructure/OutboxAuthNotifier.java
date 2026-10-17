package com.wtc.identity.infrastructure;

import com.wtc.identity.application.port.AuthNotifier;
import com.wtc.identity.domain.User;
import com.wtc.shared.security.SecretCipher;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dépose la demande d'envoi dans l'outbox, dans la transaction de l'appelant : le lien n'est jamais
 * perdu ni envoyé pour une action annulée. Le jeton brut est chiffré (AES-GCM) : une lecture de la
 * table ne donne pas de lien valide. Le module de notification, qui consommera l'outbox, déchiffre
 * au moment d'envoyer.
 */
@Component
class OutboxAuthNotifier implements AuthNotifier {

    static final String CIPHER_CONTEXT = "outbox:identity-token";

    private final JdbcClient jdbc;
    private final SecretCipher cipher;
    private final JsonMapper json;

    OutboxAuthNotifier(JdbcClient jdbc, SecretCipher cipher, JsonMapper json) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.json = json;
    }

    @Override
    public void passwordResetRequested(User user, String rawToken, Instant expiresAt) {
        enqueue("identity.password-reset-requested", user, rawToken, expiresAt);
    }

    @Override
    public void staffInvited(User user, String rawToken, Instant expiresAt) {
        enqueue("identity.staff-invited", user, rawToken, expiresAt);
    }

    private void enqueue(String eventType, User user, String rawToken, Instant expiresAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", user.publicId().toString());
        payload.put("email", user.email());
        payload.put("displayName", user.displayName());
        payload.put("expiresAt", expiresAt.toString());
        payload.put(
                "tokenEncrypted",
                Base64.getEncoder().encodeToString(cipher.encrypt(rawToken, CIPHER_CONTEXT)));
        jdbc.sql(
                        """
                        INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
                        VALUES ('USER', :id, :type, CAST(:payload AS jsonb))
                        """)
                .param("id", user.id())
                .param("type", eventType)
                .param("payload", json.writeValueAsString(payload))
                .update();
    }
}
