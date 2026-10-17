/**
 * Module <b>transfer</b> : Transferts d'argent : devis, machine d'états, débit atomique,
 * remboursement unique.
 *
 * <p>Organisation interne (ADR 0001) : {@code api} (contrôleurs, DTO) → {@code application} (cas
 * d'usage, ports) → {@code domain} (règles, sans dépendance technique) ← {@code infrastructure}
 * (JPA, clients HTTP).
 */
package com.wtc.transfer;
