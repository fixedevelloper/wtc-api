/**
 * Module <b>payment</b> : Ports PayoutGateway et PayInGateway et leur adaptateur Wace Pay (outbox,
 * webhooks, réconciliation).
 *
 * <p>Organisation interne (ADR 0001) : {@code api} (contrôleurs, DTO) → {@code application} (cas
 * d'usage, ports) → {@code domain} (règles, sans dépendance technique) ← {@code infrastructure}
 * (JPA, clients HTTP).
 */
package com.wtc.payment;
