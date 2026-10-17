package com.wtc.customer.application;

import java.util.UUID;

/**
 * Coordonnées d'un client pour le joindre (notifications). {@code phone} est au format E.164, vide
 * s'il manque.
 */
public record CustomerContact(UUID customerId, String displayName, String email, String phone) {}
