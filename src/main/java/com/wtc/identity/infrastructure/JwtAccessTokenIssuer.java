package com.wtc.identity.infrastructure;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.wtc.identity.application.port.AccessTokenIssuer;
import com.wtc.shared.config.WtcProperties;
import com.wtc.shared.security.PemKeys;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Signe les jetons d'accès en RS256 avec la clé privée configurée. Les jetons portent rôles et
 * permissions.
 */
@Component
@ConditionalOnProperty(prefix = "wtc.security.jwt", name = "private-key-location")
final class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final String issuer;
    private final Duration ttl;

    JwtAccessTokenIssuer(WtcProperties properties) {
        WtcProperties.Jwt jwt = properties.security().jwt();
        RSAPrivateKey privateKey = PemKeys.readRsaPrivateKey(jwt.privateKeyLocation());
        RSAPublicKey publicKey = PemKeys.publicKeyOf(privateKey);
        try {
            RSAKey key =
                    new RSAKey.Builder(publicKey)
                            .privateKey(privateKey)
                            .keyIDFromThumbprint()
                            .build();
            this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        } catch (JOSEException e) {
            throw new IllegalStateException("Clé de signature JWT inutilisable", e);
        }
        this.issuer = jwt.issuer();
        this.ttl = jwt.accessTtl();
    }

    @Override
    public Issued issue(Claims claims, Instant now) {
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet.Builder builder =
                JwtClaimsSet.builder()
                        .issuer(issuer)
                        .subject(claims.userId().toString())
                        .issuedAt(now)
                        .expiresAt(expiresAt)
                        .id(UUID.randomUUID().toString())
                        .claim("sid", claims.sessionId().toString())
                        .claim("roles", claims.roles().stream().sorted().toList())
                        .claim("permissions", claims.permissions().stream().sorted().toList());
        if (claims.customerId() != null) {
            builder.claim("customer_id", claims.customerId().toString());
        }
        if (claims.enrollmentOnly()) {
            builder.claim("enrollment_only", true);
        }
        String token =
                encoder.encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(SignatureAlgorithm.RS256).build(),
                                        builder.build()))
                        .getTokenValue();
        return new Issued(token, expiresAt, ttl.toSeconds());
    }
}
