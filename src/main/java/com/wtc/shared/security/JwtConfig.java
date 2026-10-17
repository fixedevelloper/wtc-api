package com.wtc.shared.security;

import com.wtc.shared.config.WtcProperties;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Vérification des jetons d'accès : JWT <b>RS256</b> uniquement, signature contrôlée avec la clé
 * publique configurée, expiration et émetteur vérifiés. Sans clé configurée l'application ne
 * démarre pas (échec rapide plutôt qu'accès ouvert).
 */
@Configuration(proxyBeanMethods = false)
class JwtConfig {

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder jwtDecoder(WtcProperties properties) {
        WtcProperties.Jwt jwt = properties.security().jwt();
        RSAPublicKey key;
        if (jwt.publicKeyLocation() != null) {
            key = PemKeys.readRsaPublicKey(jwt.publicKeyLocation());
        } else if (jwt.privateKeyLocation() != null) {
            key = PemKeys.publicKeyOf(PemKeys.readRsaPrivateKey(jwt.privateKeyLocation()));
        } else {
            // Échec rapide : sans clé de vérification, l'API ne doit pas démarrer (jamais d'accès
            // ouvert par défaut).
            throw new IllegalStateException(
                    "Aucune clé JWT : renseignez wtc.security.jwt.public-key-location ou"
                            + " private-key-location");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key).build();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        if (jwt.issuer() == null || jwt.issuer().isBlank()) {
            validators.add(JwtValidators.createDefault());
        } else {
            validators.add(JwtValidators.createDefaultWithIssuer(jwt.issuer()));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    /**
     * Autorités déduites du jeton : claim {@code roles} (préfixe {@code ROLE_}) et claim {@code
     * permissions} (telles quelles, par exemple {@code TRANSFER_READ}).
     */
    @Bean
    Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                jwt -> {
                    Collection<GrantedAuthority> authorities = new ArrayList<>();
                    claim(jwt, "roles")
                            .forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
                    claim(jwt, "permissions")
                            .forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
                    return authorities;
                });
        return converter;
    }

    private static List<String> claim(Jwt jwt, String name) {
        List<String> values = jwt.getClaimAsStringList(name);
        return values == null ? List.of() : values;
    }
}
