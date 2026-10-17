package com.wtc.reference.domain;

import java.util.Objects;

/**
 * Version minimale (et dernière disponible) d'une application cliente ; sert à imposer une mise à
 * jour.
 */
public record ClientRequirement(
        String platform, String minimumVersion, String latestVersion, String updateUrl) {

    public ClientRequirement {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(minimumVersion, "minimumVersion");
    }
}
