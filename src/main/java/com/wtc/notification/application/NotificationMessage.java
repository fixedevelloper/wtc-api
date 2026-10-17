package com.wtc.notification.application;

/** Une notification à envoyer, telle que stockée dans la file ({@code notification_outbox}). */
public record NotificationMessage(
        long id,
        Channel channel,
        String recipient,
        String template,
        String payloadJson,
        int attempts) {

    @Override
    public String toString() {
        return "NotificationMessage["
                + id
                + ", "
                + channel
                + ", "
                + template
                + "]"; // jamais le destinataire ni le contenu
    }
}
