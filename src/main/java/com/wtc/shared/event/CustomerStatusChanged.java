package com.wtc.shared.event;

import java.util.UUID;

/**
 * Le statut d'un client a changé ({@code ACTIVE}, {@code SUSPENDED}, {@code CLOSED}). Publié par le
 * module client, consommé par l'identité qui suspend ou rétablit le compte d'accès et ferme ses
 * sessions. Passe par un événement pour que les deux modules ne dépendent pas l'un de l'autre.
 *
 * @param userId identifiant interne du compte d'accès (nul pour un client créé par le back-office)
 */
public record CustomerStatusChanged(UUID customerId, Long userId, String status) {}
