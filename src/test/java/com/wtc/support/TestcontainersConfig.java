package com.wtc.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL 16 et MinIO jetables pour les tests d'intégration (mêmes moteurs qu'en production,
 * jamais H2 ni un faux).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    public static final String IMAGE = "postgres:16-alpine";
    public static final String MINIO_IMAGE = "minio/minio:latest";
    public static final String MINIO_USER = "wtc-test-user";
    public static final String MINIO_PASSWORD = "wtc-test-password";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse(IMAGE));
    }

    @Bean
    GenericContainer<?> minio() {
        return new GenericContainer<>(DockerImageName.parse(MINIO_IMAGE))
                .withEnv("MINIO_ROOT_USER", MINIO_USER)
                .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
                .withCommand("server", "/data")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    }

    @Bean
    DynamicPropertyRegistrar storageProperties(GenericContainer<?> minio) {
        return registry -> {
            registry.add(
                    "wtc.storage.endpoint",
                    () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
            registry.add("wtc.storage.access-key", () -> MINIO_USER);
            registry.add("wtc.storage.secret-key", () -> MINIO_PASSWORD);
            registry.add("wtc.storage.auto-create-bucket", () -> "true");
        };
    }
}
