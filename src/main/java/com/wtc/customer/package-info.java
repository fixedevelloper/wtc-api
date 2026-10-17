/**
 * Module <b>customer</b> : Clients, parties (expéditeurs et bénéficiaires), destinations de
 * versement et documents KYC.
 *
 * <p>Organisation interne (ADR 0001) : {@code api} (contrôleurs, DTO) → {@code application} (cas
 * d'usage, ports) → {@code domain} (règles, sans dépendance technique) ← {@code infrastructure}
 * (JPA, clients HTTP).
 */
package com.wtc.customer;
