package com.wtc.identity.infrastructure;

import com.wtc.identity.application.SuperAdminBootstrap;
import com.wtc.shared.config.WtcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Au démarrage, crée le premier super-administrateur si les variables {@code WTC_BOOTSTRAP_*} sont
 * renseignées.
 */
@Component
class SuperAdminBootstrapRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SuperAdminBootstrapRunner.class);

    private final SuperAdminBootstrap bootstrap;
    private final WtcProperties.Bootstrap settings;

    SuperAdminBootstrapRunner(SuperAdminBootstrap bootstrap, WtcProperties properties) {
        this.bootstrap = bootstrap;
        this.settings = properties.bootstrap();
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = settings.superAdminEmail();
        String password = settings.superAdminPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        if (bootstrap.ensureSuperAdmin(email, password)) {
            LOG.warn(
                    "Premier super-administrateur créé ({}). Activez son second facteur à la"
                        + " première connexion, puis retirez WTC_BOOTSTRAP_SUPER_ADMIN_PASSWORD de"
                        + " l'environnement.",
                    email);
        }
    }
}
