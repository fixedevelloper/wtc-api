package com.wtc.reference.api;

import com.wtc.reference.domain.ClientRequirement;

/** Représentation JSON d'une exigence de version (schéma {@code ClientRequirements}). */
public record ClientRequirementResponse(
        String platform, String minimumVersion, String latestVersion, String updateUrl) {

    static ClientRequirementResponse from(ClientRequirement r) {
        return new ClientRequirementResponse(
                r.platform(), r.minimumVersion(), r.latestVersion(), r.updateUrl());
    }
}
