package com.wtc.notification.infrastructure;

import com.wtc.notification.application.Channel;
import com.wtc.notification.application.NotificationMessage;
import com.wtc.notification.application.NotificationSender;
import com.wtc.notification.application.Rendered;
import com.wtc.notification.application.SendResult;
import com.wtc.shared.config.WtcProperties;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Alertes à l'équipe par un bot Telegram (texte brut : aucun balisage, donc rien à échapper). */
final class TelegramBotSender implements NotificationSender {

    private final WtcProperties.Telegram config;
    private final JsonMapper json;
    private final HttpJsonSender http = new HttpJsonSender();

    TelegramBotSender(WtcProperties.Telegram config, JsonMapper json) {
        if (config.botToken() == null || config.botToken().isBlank()) {
            throw new IllegalStateException(
                    "wtc.notification.telegram.bot-token est obligatoire avec le fournisseur «"
                            + " telegram ».");
        }
        this.config = config;
        this.json = json;
    }

    @Override
    public Channel channel() {
        return Channel.TELEGRAM;
    }

    @Override
    public SendResult send(NotificationMessage message, Rendered content) {
        String url =
                config.apiBaseUrl().replaceAll("/+$", "")
                        + "/bot"
                        + config.botToken()
                        + "/sendMessage";
        String body =
                json.writeValueAsString(
                        Map.of(
                                "chat_id",
                                message.recipient(),
                                "text",
                                content.text(),
                                "disable_web_page_preview",
                                true));
        return http.post(url, body, Map.of());
    }
}
