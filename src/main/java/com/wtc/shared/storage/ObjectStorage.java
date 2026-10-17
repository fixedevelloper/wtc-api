package com.wtc.shared.storage;

import java.time.Duration;

/**
 * Stockage d'objets privés (pièces d'identité, photos). Les objets ne sont jamais publics : on
 * remet au client un lien signé de courte durée.
 */
public interface ObjectStorage {

    void put(String key, byte[] content, String contentType);

    void delete(String key);

    /** Lien de téléchargement signé, valable {@code ttl}. */
    String presignedGetUrl(String key, Duration ttl);
}
