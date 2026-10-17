/**
 * Module <b>notification</b> : Outbox de notifications et canaux (e-mail, WhatsApp, Telegram).
 *
 * <p>Organisation interne (ADR 0001) : {@code api} (contrôleurs, DTO) → {@code application} (cas
 * d'usage, ports) → {@code domain} (règles, sans dépendance technique) ← {@code infrastructure}
 * (JPA, clients HTTP).
 */
package com.wtc.notification;
