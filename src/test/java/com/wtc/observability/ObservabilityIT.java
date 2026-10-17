package com.wtc.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.support.TestJwt;
import com.wtc.support.TestcontainersConfig;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Supervision : sondes de vie et de disponibilité, métriques Prometheus sur un port interne
 * distinct du port public.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ObservabilityIT {

    @DynamicPropertySource
    static void jwt(DynamicPropertyRegistry registry) {
        registry.add("wtc.security.jwt.public-key-location", TestJwt::publicKeyLocation);
        registry.add("wtc.security.jwt.private-key-location", TestJwt::privateKeyLocation);
        registry.add("wtc.security.jwt.issuer", () -> TestJwt.ISSUER);
    }

    @LocalServerPort int appPort;

    @LocalManagementPort int managementPort;

    private final RestClient rest = RestClient.create();

    /** Réponse HTTP minimale : on veut aussi observer les 401/404 sans exception. */
    private record Reply(int status, String body) {}

    private Reply get(int port, String path) {
        return rest.get()
                .uri("http://localhost:" + port + path)
                .exchange(
                        (request, response) ->
                                new Reply(
                                        response.getStatusCode().value(),
                                        new String(
                                                response.getBody().readAllBytes(),
                                                StandardCharsets.UTF_8)));
    }

    @Test
    void managementUsesADifferentPortFromTheApi() {
        assertThat(managementPort).isNotEqualTo(appPort);
    }

    @Test
    void livenessAndReadinessAreUpAndReadinessChecksTheDatabase() {
        assertThat(get(managementPort, "/actuator/health/liveness").status()).isEqualTo(200);
        Reply readiness = get(managementPort, "/actuator/health/readiness");
        assertThat(readiness.status()).isEqualTo(200);
        assertThat(readiness.body())
                .contains("UP")
                .doesNotContain("jdbc:")
                .doesNotContain("password");
    }

    @Test
    void prometheusMetricsAreExposedOnTheManagementPortWithHttpTimings() {
        get(appPort, "/api/countries"); // génère au moins une mesure http_server_requests
        Reply metrics = get(managementPort, "/actuator/prometheus");
        assertThat(metrics.status()).isEqualTo(200);
        assertThat(metrics.body())
                .contains("http_server_requests")
                .contains("jvm_memory_used_bytes");
    }

    @Test
    void actuatorIsNotReachableOnThePublicPort() {
        assertThat(get(appPort, "/actuator/prometheus").status()).isIn(401, 404);
        assertThat(get(appPort, "/actuator/env").status()).isIn(401, 404);
    }

    @Test
    void sensitiveActuatorEndpointsAreNotExposed() {
        assertThat(get(managementPort, "/actuator/env").status()).isIn(401, 403, 404);
        assertThat(get(managementPort, "/actuator/heapdump").status()).isIn(401, 403, 404);
    }
}
