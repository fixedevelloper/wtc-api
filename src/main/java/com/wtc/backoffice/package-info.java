/**
 * Module <b>backoffice</b> : Cas d'usage d'administration transverses : audit, paramètres,
 * rapports.
 *
 * <p>Organisation interne (ADR 0001) : {@code api} (contrôleurs, DTO) → {@code application} (cas
 * d'usage, ports) → {@code domain} (règles, sans dépendance technique) ← {@code infrastructure}
 * (JPA, clients HTTP).
 */
package com.wtc.backoffice;
