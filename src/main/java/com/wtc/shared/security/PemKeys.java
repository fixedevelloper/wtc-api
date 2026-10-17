package com.wtc.shared.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.core.io.Resource;

/**
 * Lecture de clés RSA au format PEM : publique (« BEGIN PUBLIC KEY ») ou privée PKCS#8 (« BEGIN
 * PRIVATE KEY »).
 */
public final class PemKeys {

    private PemKeys() {}

    public static RSAPublicKey readRsaPublicKey(Resource resource) {
        return parseRsaPublicKey(read(resource));
    }

    public static RSAPrivateKey readRsaPrivateKey(Resource resource) {
        return parseRsaPrivateKey(read(resource));
    }

    public static RSAPublicKey parseRsaPublicKey(String pem) {
        try {
            byte[] der = decode(pem, "PUBLIC KEY");
            return (RSAPublicKey)
                    KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Clé publique RSA invalide (format PEM X.509 attendu)", e);
        }
    }

    public static RSAPrivateKey parseRsaPrivateKey(String pem) {
        try {
            byte[] der = decode(pem, "PRIVATE KEY");
            return (RSAPrivateKey)
                    KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Clé privée RSA invalide (format PEM PKCS#8 attendu : openssl genpkey"
                            + " -algorithm RSA)",
                    e);
        }
    }

    /** Déduit la clé publique d'une clé privée RSA (format CRT). */
    public static RSAPublicKey publicKeyOf(RSAPrivateKey privateKey) {
        if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
            throw new IllegalStateException(
                    "La clé privée RSA doit être au format CRT pour en déduire la clé publique");
        }
        try {
            return (RSAPublicKey)
                    KeyFactory.getInstance("RSA")
                            .generatePublic(
                                    new RSAPublicKeySpec(
                                            crt.getModulus(), crt.getPublicExponent()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Clé JWT illisible : " + resource, e);
        }
    }

    private static byte[] decode(String pem, String label) {
        String base64 =
                pem.replace("-----BEGIN " + label + "-----", "")
                        .replace("-----END " + label + "-----", "")
                        .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
