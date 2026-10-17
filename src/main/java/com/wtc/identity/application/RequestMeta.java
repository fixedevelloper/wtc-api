package com.wtc.identity.application;

/**
 * Origine de la requête, transmise par la couche API (adresse IP vue par le serveur, agent
 * utilisateur).
 */
public record RequestMeta(String ip, String userAgent) {}
