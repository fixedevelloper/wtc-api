package com.wtc.identity.domain;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mot de passe à usage unique basé sur le temps (RFC 6238 : HMAC-SHA1, pas de 30 s, 6 chiffres),
 * compatible avec Google Authenticator, Authy, Microsoft Authenticator, etc. Une tolérance d'un pas
 * de chaque côté absorbe le décalage d'horloge. Un code ne peut servir qu'une fois : l'appelant
 * mémorise le dernier pas accepté et l'impose à {@link #verify}.
 */
public final class Totp {

    public static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int WINDOW = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    /** Secret de 160 bits (taille recommandée par la RFC 4226). */
    public static byte[] newSecret() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return secret;
    }

    public static String toBase32(byte[] secret) {
        return Base32.encode(secret);
    }

    public static byte[] fromBase32(String secret) {
        return Base32.decode(secret);
    }

    public static long stepAt(long epochSeconds) {
        return epochSeconds / STEP_SECONDS;
    }

    public static String codeAt(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary =
                    ((hash[offset] & 0x7F) << 24)
                            | ((hash[offset + 1] & 0xFF) << 16)
                            | ((hash[offset + 2] & 0xFF) << 8)
                            | (hash[offset + 3] & 0xFF);
            return String.format("%0" + DIGITS + "d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @param lastUsedStep dernier pas déjà accepté pour cet utilisateur (null si aucun) : tout pas
     *     inférieur ou égal est refusé
     * @return le pas correspondant au code, ou vide si le code est faux ou déjà utilisé
     */
    public static OptionalLong verify(
            byte[] secret, String code, long epochSeconds, Long lastUsedStep) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        long current = stepAt(epochSeconds);
        OptionalLong match = OptionalLong.empty();
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            // Comparaison en temps constant, sur toutes les positions de la fenêtre.
            boolean equal =
                    MessageDigest.isEqual(
                            codeAt(secret, step).getBytes(StandardCharsets.US_ASCII),
                            code.getBytes(StandardCharsets.US_ASCII));
            if (equal && (lastUsedStep == null || step > lastUsedStep)) {
                match = OptionalLong.of(step);
            }
        }
        return match;
    }

    /** URI {@code otpauth://} lue par les applications d'authentification (code QR ou saisie). */
    public static String otpauthUri(String issuer, String account, byte[] secret) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/"
                + label
                + "?secret="
                + toBase32(secret)
                + "&issuer="
                + encode(issuer)
                + "&algorithm=SHA1&digits="
                + DIGITS
                + "&period="
                + STEP_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
