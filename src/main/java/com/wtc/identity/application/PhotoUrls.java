package com.wtc.identity.application;

import com.wtc.identity.domain.User;
import com.wtc.shared.storage.ObjectStorage;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Lien signé (1 heure) vers la photo de profil d'un compte, s'il en a une. */
@Component
class PhotoUrls {

    private static final Duration TTL = Duration.ofHours(1);

    private final ObjectStorage storage;

    PhotoUrls(ObjectStorage storage) {
        this.storage = storage;
    }

    String urlOf(User user) {
        return user.photoStorageKey() == null
                ? null
                : storage.presignedGetUrl(user.photoStorageKey(), TTL);
    }
}
