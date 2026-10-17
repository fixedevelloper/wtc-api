package com.wtc.shared.event;

/**
 * Le nom d'affichage ou le téléphone d'un client a changé : le compte d'accès (module identité)
 * doit rester cohérent. Le consommateur peut refuser (téléphone déjà pris) en levant une exception,
 * ce qui annule la modification.
 */
public record CustomerIdentityChanged(long userId, String displayName, String phoneE164) {}
