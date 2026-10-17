package com.wtc.migration;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Active l'ETL avec le profil {@code etl} ; il s'exécute au démarrage quand {@code
 * wtc.etl.run=true} (fichier application-etl.yml).
 */
@Configuration(proxyBeanMethods = false)
@Profile("etl")
class EtlConfig {

    @Bean
    @ConditionalOnProperty(prefix = "wtc.etl", name = "run", havingValue = "true")
    ApplicationRunner etlRunner(LegacyMigration migration, ConfigurableApplicationContext context) {
        return (ApplicationArguments args) -> {
            EtlResult result = migration.run();
            int code = result.controlsPassed() ? 0 : 2;
            System.exit(org.springframework.boot.SpringApplication.exit(context, () -> code));
        };
    }
}
