package com.wtc.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base des tests d'intégration : application complète, PostgreSQL réel (Testcontainers), migrations
 * Flyway appliquées et vraie vérification de signature des jetons (clé de test écrite en PEM puis
 * lue par {@code JwtConfig}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TestClock.Config.class})
@ActiveProfiles("test")
public abstract class AbstractIT {

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("wtc.security.jwt.public-key-location", TestJwt::publicKeyLocation);
        registry.add("wtc.security.jwt.private-key-location", TestJwt::privateKeyLocation);
        registry.add("wtc.security.jwt.issuer", () -> TestJwt.ISSUER);
    }
}
