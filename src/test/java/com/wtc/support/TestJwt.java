package com.wtc.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Jetons signés pour les tests. La clé publique est écrite en PEM et injectée comme en production.
 */
public final class TestJwt {

    public static final String ISSUER = "wtc-test";

    private static final KeyPair KEYS = generate();
    private static final KeyPair OTHER_KEYS = generate();
    private static final Path PUBLIC_PEM = writePem(KEYS);
    private static final Path PRIVATE_PEM = writePrivatePem(KEYS);

    private TestJwt() {}

    /** Valeur de {@code wtc.security.jwt.public-key-location}. */
    public static String publicKeyLocation() {
        return "file:" + PUBLIC_PEM;
    }

    /**
     * Valeur de {@code wtc.security.jwt.private-key-location} : l'application signe ses propres
     * jetons avec cette clé.
     */
    public static String privateKeyLocation() {
        return "file:" + PRIVATE_PEM;
    }

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) KEYS.getPublic();
    }

    public static String valid(List<String> roles, List<String> permissions) {
        return sign(
                claims(ISSUER, Instant.now().plusSeconds(600), roles, permissions),
                KEYS,
                JWSAlgorithm.RS256);
    }

    public static String expired() {
        return sign(
                claims(ISSUER, Instant.now().minusSeconds(60), List.of(), List.of()),
                KEYS,
                JWSAlgorithm.RS256);
    }

    public static String wrongIssuer() {
        return sign(
                claims("pirate", Instant.now().plusSeconds(600), List.of(), List.of()),
                KEYS,
                JWSAlgorithm.RS256);
    }

    public static String signedByAnotherKey() {
        return sign(
                claims(ISSUER, Instant.now().plusSeconds(600), List.of(), List.of()),
                OTHER_KEYS,
                JWSAlgorithm.RS256);
    }

    /** Attaque classique : jeton HS256 « signé » avec la clé publique comme secret. */
    public static String hmacWithPublicKey() {
        try {
            SignedJWT jwt =
                    new SignedJWT(
                            new JWSHeader(JWSAlgorithm.HS256),
                            claims(
                                    ISSUER,
                                    Instant.now().plusSeconds(600),
                                    List.of("ADMIN"),
                                    List.of("TRANSFER_READ")));
            jwt.sign(new MACSigner(KEYS.getPublic().getEncoded()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWTClaimsSet claims(
            String issuer, Instant expiresAt, List<String> roles, List<String> permissions) {
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(UUID.randomUUID().toString())
                .issueTime(new Date())
                .expirationTime(Date.from(expiresAt))
                .claim("roles", roles)
                .claim("permissions", permissions)
                .build();
    }

    private static String sign(JWTClaimsSet claims, KeyPair keys, JWSAlgorithm algorithm) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(algorithm), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writePem(KeyPair keys) {
        try {
            Path file = Files.createTempFile("wtc-test-jwt-", ".pem");
            file.toFile().deleteOnExit();
            String body =
                    Base64.getMimeEncoder(64, "\n".getBytes())
                            .encodeToString(keys.getPublic().getEncoded());
            Files.writeString(
                    file, "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n");
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writePrivatePem(KeyPair keys) {
        try {
            Path file = Files.createTempFile("wtc-test-jwt-private-", ".pem");
            file.toFile().deleteOnExit();
            String body =
                    Base64.getMimeEncoder(64, "\n".getBytes())
                            .encodeToString(keys.getPrivate().getEncoded());
            Files.writeString(
                    file, "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n");
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
