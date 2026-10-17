package com.wtc.notification.infrastructure;

import com.wtc.notification.application.Channel;
import com.wtc.notification.application.NotificationMessage;
import com.wtc.notification.application.NotificationSender;
import com.wtc.notification.application.Rendered;
import com.wtc.notification.application.SendResult;
import com.wtc.shared.config.WtcProperties;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * WhatsApp Business Cloud API (Meta). Les messages initiés par l'entreprise doivent utiliser un
 * <b>modèle approuvé</b> dans la console Meta : leur nom et l'ordre de leurs paramètres sont
 * définis dans {@code NotificationTemplates}.
 */
final class WabaWhatsAppSender implements NotificationSender {

    private final WtcProperties.Whatsapp config;
    private final JsonMapper json;
    private final HttpJsonSender http = new HttpJsonSender();

    WabaWhatsAppSender(WtcProperties.Whatsapp config, JsonMapper json) {
        if (config.phoneNumberId() == null
                || config.phoneNumberId().isBlank()
                || config.accessToken() == null
                || config.accessToken().isBlank()) {
            throw new IllegalStateException(
                    "wtc.notification.whatsapp.phone-number-id et access-token sont obligatoires"
                            + " avec le fournisseur « waba ».");
        }
        this.config = config;
        this.json = json;
    }

    @Override
    public Channel channel() {
        return Channel.WHATSAPP;
    }

    @Override
    public SendResult send(NotificationMessage message, Rendered content) {
        if (content.whatsappTemplate() == null) {
            return SendResult.skipped("Pas de modèle WhatsApp");
        }
        String to =
                message.recipient()
                        .replaceAll("[^0-9]", ""); // l'API attend les chiffres seuls (sans « + »)
        if (to.length() < 7) {
            return SendResult.permanent("Numéro invalide");
        }
        List<Map<String, String>> parameters =
                content.whatsappParams().stream()
                        .map(p -> Map.of("type", "text", "text", p))
                        .toList();
        Map<String, Object> body =
                Map.of(
                        "messaging_product",
                        "whatsapp",
                        "to",
                        to,
                        "type",
                        "template",
                        "template",
                        Map.of(
                                "name", content.whatsappTemplate(),
                                "language", Map.of("code", config.languageCode()),
                                "components",
                                        List.of(Map.of("type", "body", "parameters", parameters))));
        String url =
                config.apiBaseUrl().replaceAll("/+$", "")
                        + "/"
                        + config.phoneNumberId()
                        + "/messages";
        return http.post(
                url,
                json.writeValueAsString(body),
                Map.of("Authorization", "Bearer " + config.accessToken()));
    }
}
