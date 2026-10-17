package com.wtc.reference.application.port;

import com.wtc.reference.application.SettingView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettingsRepository {

    List<SettingView> list();

    Optional<SettingView> find(String key);

    /** Met à jour un paramètre existant ; l'auteur est retrouvé par son identifiant public. */
    void update(String key, String valueJson, UUID updatedBy);
}
