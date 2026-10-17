/**
 * Module <b>reference</b> : Pays, villes, banques, opérateurs, barèmes de frais, taux de change et
 * libellés pilotés par Wace.
 *
 * <p>Organisation interne (ADR 0001) : {@code api} (contrôleurs, DTO) → {@code application} (cas
 * d'usage, ports) → {@code domain} (règles, sans dépendance technique) ← {@code infrastructure}
 * (JPA, clients HTTP).
 */
package com.wtc.reference;
