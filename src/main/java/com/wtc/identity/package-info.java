/**
 * Module <b>identity</b> : Comptes, authentification (JWT, refresh à rotation, 2FA), rôles et
 * permissions, journal des connexions.
 *
 * <p>Organisation interne (ADR 0001) : {@code api} (contrôleurs, DTO) → {@code application} (cas
 * d'usage, ports) → {@code domain} (règles, sans dépendance technique) ← {@code infrastructure}
 * (JPA, clients HTTP).
 */
package com.wtc.identity;
