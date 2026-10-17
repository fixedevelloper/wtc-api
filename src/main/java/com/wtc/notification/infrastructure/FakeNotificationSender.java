package com.wtc.notification.infrastructure;

import com.wtc.notification.application.Channel;
import com.wtc.notification.application.NotificationMessage;
import com.wtc.notification.application.NotificationSender;
import com.wtc.notification.application.Rendered;
import com.wtc.notification.application.SendResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** Faux canal pilotable : enregistre ce qui serait envoyé. Développement et tests uniquement. */
public final class FakeNotificationSender implements NotificationSender {

    /** Message « envoyé » : destinataire et contenu rendu. */
    public record Sent(String recipient, String template, Rendered content) {}

    private final Channel channel;
    private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
    private final Deque<SendResult> scripted = new ArrayDeque<>();

    public FakeNotificationSender(Channel channel) {
        this.channel = channel;
    }

    /** Le prochain envoi aura ce résultat (une fois). */
    public synchronized void scriptNext(SendResult result) {
        scripted.add(result);
    }

    public List<Sent> sent() {
        synchronized (sent) {
            return List.copyOf(sent);
        }
    }

    public void reset() {
        sent.clear();
        synchronized (this) {
            scripted.clear();
        }
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public SendResult send(NotificationMessage message, Rendered content) {
        SendResult next;
        synchronized (this) {
            next = scripted.poll();
        }
        if (next != null) {
            return next;
        }
        sent.add(new Sent(message.recipient(), message.template(), content));
        return SendResult.sent();
    }
}
