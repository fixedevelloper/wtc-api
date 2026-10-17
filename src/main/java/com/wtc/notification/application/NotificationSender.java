package com.wtc.notification.application;

/** Un canal d'envoi. Ne lève jamais d'exception : toute panne est une {@link SendResult}. */
public interface NotificationSender {

    Channel channel();

    SendResult send(NotificationMessage message, Rendered content);
}
