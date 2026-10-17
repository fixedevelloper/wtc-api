package com.wtc.shared.security;

import com.wtc.shared.config.WtcProperties;
import java.util.List;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Sécurité HTTP : API sans état, jeton Bearer uniquement, pas de cookie donc pas de CSRF. Tout est
 * refusé par défaut ; seules les routes publiques du contrat sont ouvertes (un test vérifie que
 * cette liste reste identique au contrat). Les permissions fines sont portées par
 * {@code @PreAuthorize} sur chaque cas d'usage d'administration.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            ProblemSecurityHandlers handlers,
            Converter<Jwt, AbstractAuthenticationToken> jwtConverter,
            Environment environment,
            WtcProperties properties)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults()) // utilise le bean `corsConfigurationSource`
                // ci-dessous
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(
                        h ->
                                h.frameOptions(f -> f.deny())
                                        .contentTypeOptions(Customizer.withDefaults())
                                        .referrerPolicy(
                                                r ->
                                                        r.policy(
                                                                ReferrerPolicyHeaderWriter
                                                                        .ReferrerPolicy
                                                                        .NO_REFERRER))
                                        .httpStrictTransportSecurity(
                                                hsts ->
                                                        hsts.includeSubDomains(true)
                                                                .maxAgeInSeconds(31_536_000))
                                        .contentSecurityPolicy(
                                                csp ->
                                                        csp.policyDirectives(
                                                                "default-src 'none';"
                                                                    + " frame-ancestors 'none'")))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(EndpointRequest.to("health", "info"))
                                        .permitAll()
                                        // Port de supervision interne (Prometheus...) : jamais
                                        // ouvert sur le port public.
                                        .requestMatchers(
                                                new AndRequestMatcher(
                                                        notOnPublicPort(environment),
                                                        PathPatternRequestMatcher.withDefaults()
                                                                .matcher("/actuator/**")))
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/countries",
                                                "/api/countries/*/cities",
                                                "/api/meta/client-requirements",
                                                "/api/docs/openapi.yaml")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/auth/register",
                                                "/api/auth/login",
                                                "/api/auth/mfa/verify",
                                                "/api/auth/refresh",
                                                "/api/auth/password-reset/request",
                                                "/api/auth/password-reset/confirm")
                                        .permitAll()
                                        .requestMatchers("/api/web-tokens/**")
                                        .hasAuthority(ServiceKeyFilter.AUTHORITY)
                                        .anyRequest()
                                        .authenticated())
                .addFilterBefore(
                        new ServiceKeyFilter(properties.security().serviceKey()),
                        org.springframework.security.oauth2.server.resource.web.authentication
                                .BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(
                        o ->
                                o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter))
                                        .authenticationEntryPoint(handlers)
                                        .accessDeniedHandler(handlers))
                .exceptionHandling(
                        e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers));
        return http.build();
    }

    /**
     * Vrai pour une requête arrivée sur un port <b>autre que le port public</b>. Le serveur
     * n'écoute que sur deux ports (API publique et supervision) : tout ce qui n'arrive pas sur le
     * port public vient du port interne de supervision. Si les deux ports sont identiques, ou si le
     * port public est inconnu, rien n'est ouvert au-delà de health et info.
     */
    private static RequestMatcher notOnPublicPort(Environment environment) {
        return request -> {
            Integer publicPort = environment.getProperty("local.server.port", Integer.class);
            return publicPort != null && request.getLocalPort() != publicPort;
        };
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(WtcProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(
                List.of(
                        "Authorization",
                        "Content-Type",
                        "Idempotency-Key",
                        "X-Request-Id",
                        "Api-Version"));
        config.setExposedHeaders(List.of("X-Request-Id", "Retry-After", "Location"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
