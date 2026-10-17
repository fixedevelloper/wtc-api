package com.wtc.notification.infrastructure;

import com.wtc.notification.application.Channel;
import com.wtc.notification.application.NotificationSender;
import com.wtc.shared.config.WtcProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import tools.jackson.databind.json.JsonMapper;

/**
 * Choix explicite du fournisseur de chaque canal. Un canal sans choix, ou avec un fournisseur
 * inconnu, <b>empêche le démarrage</b> ; {@code disabled} éteint le canal (ses messages sont
 * ignorés).
 */
@Configuration(proxyBeanMethods = false)
class NotificationChannelsConfig {

    private static final Set<String> KNOWN = Set.of("disabled", "fake");

    @Bean
    List<NotificationSender> notificationSenders(
            WtcProperties properties, JsonMapper json, ObjectProvider<JavaMailSender> mail) {
        WtcProperties.Notification n = properties.notification();
        List<NotificationSender> senders = new ArrayList<>();
        switch (require(n.email().provider(), "email", "smtp")) {
            case "smtp" ->
                    senders.add(
                            new SmtpEmailSender(
                                    mail.getIfAvailable(
                                            () -> {
                                                throw new IllegalStateException(
                                                        "Le fournisseur « smtp » exige"
                                                            + " spring.mail.host (serveur SMTP).");
                                            }),
                                    n.email().from()));
            case "fake" -> senders.add(new FakeNotificationSender(Channel.EMAIL));
            default -> {}
        }
        switch (require(n.whatsapp().provider(), "whatsapp", "waba")) {
            case "waba" -> senders.add(new WabaWhatsAppSender(n.whatsapp(), json));
            case "fake" -> senders.add(new FakeNotificationSender(Channel.WHATSAPP));
            default -> {}
        }
        switch (require(n.telegram().provider(), "telegram", "telegram")) {
            case "telegram" -> senders.add(new TelegramBotSender(n.telegram(), json));
            case "fake" -> senders.add(new FakeNotificationSender(Channel.TELEGRAM));
            default -> {}
        }
        return List.copyOf(senders);
    }

    /**
     * Les faux canaux sont aussi exposés individuellement pour que les tests puissent les piloter.
     */
    @Bean
    List<FakeNotificationSender> fakeNotificationSenders(List<NotificationSender> senders) {
        return senders.stream()
                .filter(FakeNotificationSender.class::isInstance)
                .map(FakeNotificationSender.class::cast)
                .toList();
    }

    private static String require(String provider, String channel, String real) {
        if (provider == null
                || provider.isBlank()
                || !(KNOWN.contains(provider) || provider.equals(real))) {
            throw new IllegalStateException(
                    "wtc.notification."
                            + channel
                            + ".provider est obligatoire : « "
                            + real
                            + " » (réel), « fake » (développement et tests uniquement) ou «"
                            + " disabled ».");
        }
        return provider;
    }
}
