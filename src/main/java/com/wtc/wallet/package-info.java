/**
 * Module <b>wallet</b> : Portefeuilles par devise, grand livre à double entrée, recharges et
 * ajustements de solde.
 *
 * <p>Organisation interne (ADR 0001) : {@code api} (contrôleurs, DTO) → {@code application} (cas
 * d'usage, ports) → {@code domain} (règles, sans dépendance technique) ← {@code infrastructure}
 * (JPA, clients HTTP).
 */
package com.wtc.wallet;
